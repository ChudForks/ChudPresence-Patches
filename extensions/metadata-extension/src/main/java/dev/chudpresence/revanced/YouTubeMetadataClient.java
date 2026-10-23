package dev.chudpresence.revanced;

import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Resolves a known playing ID, never a title search or a prefetched video. */
final class YouTubeMetadataClient {
    static TrackMetadata lookup(String videoId) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D" + videoId
        ).openConnection();
        connection.setConnectTimeout(7_000);
        connection.setReadTimeout(7_000);
        try (InputStream input = connection.getInputStream()) {
            return parse(new JSONObject(YouTubeMusicMetadataClient.readLimited(input)), videoId);
        } finally {
            connection.disconnect();
        }
    }

    static TrackMetadata parse(JSONObject response, String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return null;
        String channel = response.optString("author_url", "");
        if (!channel.matches("https://(?:www\\.)?youtube\\.com/(?:channel/[A-Za-z0-9_-]+|@[A-Za-z0-9_.%~-]+)")) channel = null;
        return new TrackMetadata(null, null, 0, "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg",
            videoId, channel, null, response.optString("title", ""), response.optString("author_name", ""));
    }
}
