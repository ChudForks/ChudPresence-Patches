package dev.chudpresence.revanced;

import android.media.MediaMetadata;
import android.media.session.MediaSession;

/** Injected only at Hulu's playback-start and playable-item-change callbacks. */
public final class HuluMetadataBridge {
    private static final String PREFIX = "dev.chudpresence.metadata.";

    private HuluMetadataBridge() { }

    public static void publish(MediaSession session, Object entity) {
        if (session == null) return;
        try {
            HuluMetadata item = HuluMetadata.fromEntity(entity);
            if (item == null) {
                session.setMetadata(null);
                return;
            }
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item.title != null ? item.title : "Hulu")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item.series)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, item.album)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, item.series)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, item.album)
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, item.mediaId)
                .putString(MediaMetadata.METADATA_KEY_ART_URI, item.artworkUrl)
                .putString(MediaMetadata.METADATA_KEY_MEDIA_URI, item.contentUrl)
                .putString(PREFIX + "CONTENT_URL", item.contentUrl)
                .putString(PREFIX + "CONTENT_TYPE", item.contentType)
                .putLong(PREFIX + "VERSION", 1);
            if (item.durationMs > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, item.durationMs);
            if (item.season != null) metadata.putLong(PREFIX + "SEASON_NUMBER", item.season);
            if (item.episode != null) metadata.putString(PREFIX + "EPISODE_NUMBER", item.episode.toString());
            // Always replace the snapshot; a movie or missing field must not retain the prior episode.
            session.setMetadata(metadata.build());
        } catch (RuntimeException | LinkageError ignored) {
            // Descriptive metadata must never interrupt the stock player's playback callbacks.
        }
    }
}
