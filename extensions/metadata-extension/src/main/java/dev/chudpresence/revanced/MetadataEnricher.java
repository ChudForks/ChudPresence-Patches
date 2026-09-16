package dev.chudpresence.revanced;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.net.Uri;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime half of the Enrich media metadata patch. */
public final class MetadataEnricher {
    private static final Pattern VIDEO_ID = Pattern.compile("(?:^|[/=])([A-Za-z0-9_-]{11})(?:$|[?&#/])");
    private static final Object LOCK = new Object();
    private static final ExecutorService LOOKUPS = Executors.newSingleThreadExecutor();
    private static final Set<String> PENDING = Collections.newSetFromMap(new java.util.HashMap<>());
    private static final Map<MediaSession, String> CURRENT_TRACK = new WeakHashMap<>();
    private static final Map<MediaSession, MediaMetadata> LATEST_METADATA = new WeakHashMap<>();
    private static final Map<String, TrackMetadata> CACHE =
        new LinkedHashMap<String, TrackMetadata>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TrackMetadata> eldest) {
                return size() > 256;
            }
        };

    private MetadataEnricher() {
    }

    /**
     * Drop-in replacement for MediaSession.setMetadata. This method must never block because
     * YouTube Music invokes it on its playback/main path.
     */
    public static void setMetadata(MediaSession session, MediaMetadata metadata) {
        if (metadata == null) {
            synchronized (LOCK) {
                session.setMetadata(null);
                CURRENT_TRACK.remove(session);
                LATEST_METADATA.remove(session);
            }
            return;
        }

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String videoId = extractVideoId(metadata);
        String trackKey = videoId != null ? videoId : normalizedKey(title, artist);
        synchronized (LOCK) {
            // Publishing and recording the latest snapshot under one lock prevents a completed
            // background lookup from restoring an older cover after YouTube Music updates it.
            session.setMetadata(metadata);
            if (trackKey == null) {
                CURRENT_TRACK.remove(session);
                LATEST_METADATA.remove(session);
            } else {
                CURRENT_TRACK.put(session, trackKey);
                LATEST_METADATA.put(session, metadata);
            }
        }
        if (trackKey == null || (hasText(metadata, MediaMetadata.METADATA_KEY_ALBUM)
            && hasPublicArtwork(metadata))) return;

        TrackMetadata cached;
        synchronized (LOCK) {
            cached = CACHE.get(trackKey);
            if (cached == null && !PENDING.add(trackKey)) return;
        }

        if (cached != null) {
            publishIfCurrent(session, trackKey, cached);
            return;
        }

        LOOKUPS.execute(() -> {
            TrackMetadata resolved = null;
            try {
                resolved = YouTubeMusicMetadataClient.lookup(videoId, title, artist);
            } catch (Exception ignored) {
                // Metadata enrichment is best-effort and must never affect playback.
            }

            synchronized (LOCK) {
                PENDING.remove(trackKey);
                if (resolved != null && !resolved.isEmpty()) CACHE.put(trackKey, resolved);
            }
            if (resolved != null && !resolved.isEmpty()) {
                publishIfCurrent(session, trackKey, resolved);
            }
        });
    }

    private static void publishIfCurrent(
        MediaSession session,
        String trackKey,
        TrackMetadata extra
    ) {
        synchronized (LOCK) {
            if (!trackKey.equals(CURRENT_TRACK.get(session))) return;
            MediaMetadata latest = LATEST_METADATA.get(session);
            if (latest == null) return;
            MediaMetadata.Builder builder = new MediaMetadata.Builder(latest);
            if (extra.album != null) builder.putString(MediaMetadata.METADATA_KEY_ALBUM, extra.album);
            if (extra.albumArtist != null) {
                builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, extra.albumArtist);
            }
            if (extra.year > 0) builder.putLong(MediaMetadata.METADATA_KEY_YEAR, extra.year);
            if (extra.artworkUrl != null && !hasPublicArtwork(latest)) {
                builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, extra.artworkUrl);
            }
            MediaMetadata enriched = builder.build();
            LATEST_METADATA.put(session, enriched);
            session.setMetadata(enriched);
        }
    }

    private static boolean hasText(MediaMetadata metadata, String key) {
        String value = metadata.getString(key);
        return value != null && !value.trim().isEmpty();
    }

    private static boolean hasPublicArtwork(MediaMetadata metadata) {
        String[] candidates = {
            metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        };
        for (String candidate : candidates) {
            if (candidate != null && candidate.trim().startsWith("https://")
                && candidate.trim().length() <= 300) return true;
        }
        return false;
    }

    private static String extractVideoId(MediaMetadata metadata) {
        String[] candidates = {
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        };
        for (String candidate : candidates) {
            String id = videoIdFrom(candidate);
            if (id != null) return id;
        }
        return null;
    }

    private static String videoIdFrom(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.matches("[A-Za-z0-9_-]{11}")) return trimmed;

        Matcher matcher = VIDEO_ID.matcher(trimmed);
        if (matcher.find()) return matcher.group(1);
        try {
            Uri uri = Uri.parse(trimmed);
            String queryId = uri.getQueryParameter("v");
            return queryId != null && queryId.matches("[A-Za-z0-9_-]{11}") ? queryId : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String normalizedKey(String title, String artist) {
        if (title == null || title.trim().isEmpty()) return null;
        return title.trim().toLowerCase(java.util.Locale.ROOT) + "\n"
            + (artist == null ? "" : artist.trim().toLowerCase(java.util.Locale.ROOT));
    }
}
