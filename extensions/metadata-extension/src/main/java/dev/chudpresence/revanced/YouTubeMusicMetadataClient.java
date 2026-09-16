package dev.chudpresence.revanced;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

final class YouTubeMusicMetadataClient {
    // Public WEB_REMIX Innertube key also used by ytmusicapi. It identifies the client,
    // not a user, and grants no access to a Google account.
    private static final String API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
    private static final int MAX_RESPONSE_CHARS = 2 * 1024 * 1024;

    private YouTubeMusicMetadataClient() {
    }

    static TrackMetadata lookup(String videoId, String expectedTitle, String expectedArtist)
        throws IOException, JSONException {
        if (videoId != null) {
            JSONObject nextBody = requestBody().put("videoId", videoId);
            TrackMetadata result = findTrack(request("next", nextBody), videoId, expectedTitle);
            if (result != null) return result;
        }

        String query = ((expectedArtist == null ? "" : expectedArtist + " ") + expectedTitle).trim();
        if (query.isEmpty()) return null;
        JSONObject searchBody = requestBody()
            .put("query", query)
            .put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF");
        return findTrack(request("search", searchBody), videoId, expectedTitle);
    }

    private static JSONObject requestBody() throws JSONException {
        String clientVersion = currentClientVersion();
        JSONObject client = new JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", clientVersion)
            .put("hl", "en")
            .put("gl", Locale.getDefault().getCountry().isEmpty()
                ? "US" : Locale.getDefault().getCountry());
        return new JSONObject().put("context", new JSONObject().put("client", client));
    }

    private static JSONObject request(String endpoint, JSONObject body) throws IOException, JSONException {
        String clientVersion = body.getJSONObject("context").getJSONObject("client")
            .getString("clientVersion");
        URL url = new URL("https://music.youtube.com/youtubei/v1/" + endpoint
            + "?alt=json&key=" + API_KEY);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(7_000);
        connection.setReadTimeout(7_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Origin", "https://music.youtube.com");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("X-YouTube-Client-Name", "67");
        connection.setRequestProperty("X-YouTube-Client-Version", clientVersion);

        byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(request.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(request);
        }

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("YouTube Music metadata request failed with HTTP " + status);
        }

        try (InputStream input = connection.getInputStream()) {
            return new JSONObject(readLimited(input));
        } finally {
            connection.disconnect();
        }
    }

    static TrackMetadata findTrack(Object node, String videoId, String expectedTitle) {
        return findTrack(node, videoId, expectedTitle, new HashSet<>());
    }

    private static TrackMetadata findTrack(
        Object node,
        String videoId,
        String expectedTitle,
        Set<Object> visited
    ) {
        if (node == null || node == JSONObject.NULL || !visited.add(node)) return null;

        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("playlistPanelVideoRenderer");
            if (renderer == null) renderer = object.optJSONObject("musicResponsiveListItemRenderer");
            if (renderer != null && isTarget(renderer, videoId, expectedTitle)) {
                TrackMetadata metadata = parseRenderer(renderer);
                if (metadata != null && !metadata.isEmpty()) return metadata;
            }

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                TrackMetadata result = findTrack(object.opt(keys.next()), videoId, expectedTitle, visited);
                if (result != null) return result;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                TrackMetadata result = findTrack(array.opt(i), videoId, expectedTitle, visited);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static boolean isTarget(JSONObject renderer, String videoId, String expectedTitle) {
        if (videoId != null && videoId.equals(renderer.optString("videoId"))) return true;
        String title = rendererTitle(renderer);
        return title != null && expectedTitle != null && title.equalsIgnoreCase(expectedTitle.trim());
    }

    private static TrackMetadata parseRenderer(JSONObject renderer) {
        JSONArray runs = runs(renderer.optJSONObject("longBylineText"));
        if (runs == null) runs = runs(renderer.optJSONObject("subtitle"));
        if (runs == null) runs = flexColumnRuns(renderer, 1);
        if (runs == null) return null;

        String album = null;
        StringBuilder artists = new StringBuilder();
        int year = 0;
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run == null) continue;
            String text = clean(run.optString("text", null));
            if (text == null || "•".equals(text)) continue;

            JSONObject browse = run.optJSONObject("navigationEndpoint");
            if (browse != null) browse = browse.optJSONObject("browseEndpoint");
            String pageType = null;
            if (browse != null) {
                JSONObject configs = browse.optJSONObject("browseEndpointContextSupportedConfigs");
                JSONObject music = configs == null ? null
                    : configs.optJSONObject("browseEndpointContextMusicConfig");
                pageType = music == null ? null : music.optString("pageType", null);
            }

            if ("MUSIC_PAGE_TYPE_ALBUM".equals(pageType)) {
                album = text;
            } else if ("MUSIC_PAGE_TYPE_ARTIST".equals(pageType)) {
                if (artists.length() > 0) artists.append(", ");
                artists.append(text);
            } else if (text.matches("(?:19|20)\\d{2}")) {
                year = Integer.parseInt(text);
            }
        }

        String albumArtist = artists.length() == 0 ? null : artists.toString();
        return new TrackMetadata(album, albumArtist, year);
    }

    private static String rendererTitle(JSONObject renderer) {
        String direct = firstText(renderer.optJSONObject("title"));
        if (direct != null) return direct;
        JSONArray titleRuns = flexColumnRuns(renderer, 0);
        if (titleRuns == null || titleRuns.length() == 0) return null;
        JSONObject first = titleRuns.optJSONObject(0);
        return first == null ? null : clean(first.optString("text", null));
    }

    private static JSONArray flexColumnRuns(JSONObject renderer, int index) {
        JSONArray columns = renderer.optJSONArray("flexColumns");
        if (columns == null || index >= columns.length()) return null;
        JSONObject column = columns.optJSONObject(index);
        if (column == null) return null;
        JSONObject content = column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        return content == null ? null : runs(content.optJSONObject("text"));
    }

    private static JSONArray runs(JSONObject textObject) {
        return textObject == null ? null : textObject.optJSONArray("runs");
    }

    private static String firstText(JSONObject textObject) {
        JSONArray values = runs(textObject);
        if (values == null || values.length() == 0) return null;
        JSONObject first = values.optJSONObject(0);
        return first == null ? null : clean(first.optString("text", null));
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String currentClientVersion() {
        SimpleDateFormat format = new SimpleDateFormat("'1.'yyyyMMdd'.01.00'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String readLimited(InputStream input) throws IOException {
        StringBuilder result = new StringBuilder(32 * 1024);
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8 * 1024];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (result.length() + count > MAX_RESPONSE_CHARS) {
                    throw new IOException("YouTube Music metadata response was too large");
                }
                result.append(buffer, 0, count);
            }
        }
        return result.toString();
    }
}
