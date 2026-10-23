package dev.chudpresence.revanced;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.net.Uri;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Shared media-session contract. Unknown/private tracks keep the player's original metadata. */
public final class MetadataEnricher {
    private static final String PREFIX = "dev.chudpresence.metadata.";
    private static final Object LOCK = new Object();
    private static final ExecutorService LOOKUPS = Executors.newSingleThreadExecutor();
    private static final Map<MediaSession, PendingTrack> SESSIONS = new WeakHashMap<>();
    private static final Set<String> PENDING = new HashSet<>();
    private static final Map<String, Long> RETRY_AFTER = boundedMap(256);
    private static final Map<String, TrackMetadata> CACHE = boundedMap(256);
    private static final Map<String, Boolean> SHORTS = boundedMap(128);
    private static String currentYouTubeId;
    private static String currentMusicId;
    private static String currentMusicTitle;

    private static <T> Map<String, T> boundedMap(final int limit) {
        return new LinkedHashMap<String, T>(64, .75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, T> entry) { return size() > limit; }
        };
    }

    private static final class PendingTrack {
        final MediaMetadata metadata;
        final String key, videoId;
        final boolean music, captured;
        PendingTrack(MediaMetadata metadata, String key, String videoId, boolean music, boolean captured) {
            this.metadata = metadata; this.key = key; this.videoId = videoId;
            this.music = music; this.captured = captured;
        }
    }

    public static void setMetadata(MediaSession session, MediaMetadata metadata) { setOriginal(session, metadata, true); }
    public static void setYouTubeMetadata(MediaSession session, MediaMetadata metadata) { setOriginal(session, metadata, false); }

    private static void setOriginal(MediaSession session, MediaMetadata metadata, boolean music) {
        session.setMetadata(metadata);
        try { update(session, metadata, music); } catch (RuntimeException ignored) { }
    }

    /** Music's actual playback-stage event supplies the ID omitted from its media session. */
    public static void onMusicVideoDetails(String id, String title) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}") || title == null) return;
        synchronized (LOCK) {
            if (id.equals(currentMusicId) && title.equals(currentMusicTitle)) return;
            currentMusicId = id;
            currentMusicTitle = title;
            for (Map.Entry<MediaSession, PendingTrack> entry : new ArrayList<>(SESSIONS.entrySet())) {
                if (entry.getValue().music) update(entry.getKey(), entry.getValue().metadata, true);
            }
        }
    }

    /** Only foreground/background playing-ID hooks call this, never response prefetch. */
    public static void onVideoId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return;
        synchronized (LOCK) {
            if (id.equals(currentYouTubeId)) return;
            currentYouTubeId = id;
            for (Map.Entry<MediaSession, PendingTrack> entry : new ArrayList<>(SESSIONS.entrySet())) {
                if (!entry.getValue().music) update(entry.getKey(), entry.getValue().metadata, false);
            }
        }
    }

    /** Observe Shorts parameters without treating prefetched IDs as the playing video. */
    public static void onPlayerParameters(String id, String parameters, boolean openingOrPlaying) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}") || parameters == null) return;
        synchronized (LOCK) {
            boolean isShort = parameters.startsWith("8AEB");
            if (isShort && !openingOrPlaying) return;
            Boolean old = SHORTS.put(id, isShort);
            if (old != null && old == isShort) return;
            for (Map.Entry<MediaSession, PendingTrack> entry : SESSIONS.entrySet()) {
                if (!entry.getValue().music && id.equals(entry.getValue().videoId)) publish(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void update(MediaSession session, MediaMetadata metadata, boolean music) {
        synchronized (LOCK) {
            if (metadata == null) { SESSIONS.remove(session); return; }
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (title == null || title.trim().isEmpty()) { SESSIONS.remove(session); return; }
            String id = extractVideoId(metadata);
            boolean captured = false;
            if (music && id == null && YouTubeMusicMetadataClient.normalize(title).equals(
                YouTubeMusicMetadataClient.normalize(currentMusicTitle))) id = currentMusicId;
            if (!music && id == null) { id = currentYouTubeId; captured = id != null; }
            String key = (music ? "music:" : "video:") + (id == null ? "" : id) + "\n" + title + "\n" + artist;
            PendingTrack track = new PendingTrack(metadata, key, id, music, captured);
            SESSIONS.put(session, track);
            publish(session, track);
            if (CACHE.containsKey(key) || id == null
                || RETRY_AFTER.getOrDefault(key, 0L) > System.currentTimeMillis() || !PENDING.add(key)) return;
            LOOKUPS.execute(() -> resolve(track, title, artist));
        }
    }

    private static void resolve(PendingTrack track, String title, String artist) {
        synchronized (LOCK) {
            boolean current = false;
            for (PendingTrack value : SESSIONS.values()) if (track.key.equals(value.key)) { current = true; break; }
            if (!current) { PENDING.remove(track.key); return; }
        }
        TrackMetadata result = null;
        try {
            result = track.music ? YouTubeMusicMetadataClient.lookup(track.videoId, title, artist)
                : YouTubeMetadataClient.lookup(track.videoId);
        } catch (Exception ignored) { /* Network failure must never affect playback. */ }
        synchronized (LOCK) {
            PENDING.remove(track.key);
            if (result != null && !result.isEmpty()) CACHE.put(track.key, result);
            else RETRY_AFTER.put(track.key, System.currentTimeMillis() + 60_000L);
            for (Map.Entry<MediaSession, PendingTrack> entry : SESSIONS.entrySet()) {
                if (entry.getValue().key.equals(track.key)) publish(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void publish(MediaSession session, PendingTrack track) {
        try { publishUnchecked(session, track); } catch (RuntimeException ignored) {
            // Sessions may be released while the network request is in flight.
        }
    }

    private static void publishUnchecked(MediaSession session, PendingTrack track) {
        TrackMetadata extra = CACHE.get(track.key);
        // ID and media-session callbacks can arrive in either order. Do not combine a new
        // ID with the previous video's title/creator during a transition.
        if (track.captured && (extra == null
            || !YouTubeMusicMetadataClient.normalize(extra.title).equals(YouTubeMusicMetadataClient.normalize(
                track.metadata.getString(MediaMetadata.METADATA_KEY_TITLE)))
            || !YouTubeMusicMetadataClient.sameArtist(track.metadata.getString(MediaMetadata.METADATA_KEY_ARTIST), extra.artist))) return;
        String id = track.videoId != null ? track.videoId : extra == null ? null : extra.videoId;
        MediaMetadata.Builder builder = new MediaMetadata.Builder(track.metadata);
        if (id != null) {
            builder.putString(PREFIX + "VIDEO_ID", id);
            boolean shortVideo = !track.music && Boolean.TRUE.equals(SHORTS.get(id));
            String url = track.music ? "https://music.youtube.com/watch?v=" + id
                : shortVideo ? "https://www.youtube.com/shorts/" + id : "https://www.youtube.com/watch?v=" + id;
            builder.putString(PREFIX + "CONTENT_URL", url);
            builder.putString(PREFIX + "CONTENT_TYPE", shortVideo ? "short" : track.music ? "song" : "video");
        }
        if (extra != null) {
            if (extra.album != null) builder.putString(MediaMetadata.METADATA_KEY_ALBUM, extra.album);
            if (extra.albumArtist != null) builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, extra.albumArtist);
            if (extra.year > 0) builder.putLong(MediaMetadata.METADATA_KEY_YEAR, extra.year);
            if (extra.artworkUrl != null && !hasPublicArtwork(track.metadata)) {
                builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, extra.artworkUrl);
            }
            if (extra.artistUrl != null) builder.putString(PREFIX + "ARTIST_URL", extra.artistUrl);
            if (extra.albumUrl != null) builder.putString(PREFIX + "ALBUM_URL", extra.albumUrl);
        }
        builder.putLong(PREFIX + "VERSION", 1);
        session.setMetadata(builder.build());
    }

    private static boolean hasPublicArtwork(MediaMetadata metadata) {
        for (String key : new String[] { MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI }) {
            String value = metadata.getString(key);
            if (value != null && value.startsWith("https://") && value.length() <= 300) return true;
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
            if (candidate == null) continue;
            if (candidate.matches("[A-Za-z0-9_-]{11}")) return candidate;
            try {
                Uri uri = Uri.parse(candidate);
                String query = uri.isHierarchical() ? uri.getQueryParameter("v") : null;
                if (query != null && query.matches("[A-Za-z0-9_-]{11}")) return query;
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "(?:/vi/|/vi_webp/|youtu\\.be/|/shorts/|/live/)([A-Za-z0-9_-]{11})(?:[/?&#]|$)").matcher(candidate);
                if (matcher.find()) return matcher.group(1);
            } catch (RuntimeException ignored) { }
        }
        return null;
    }
}
