package dev.chudpresence.revanced;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** Reads only descriptive, already-loaded item fields. No playlist, account or DRM access. */
final class HuluMetadata {
    final String title, series, album, mediaId, artworkUrl, contentUrl, contentType;
    final long durationMs;
    final Integer season, episode;

    private HuluMetadata(Object entity) {
        title = text(read(entity, "getName"));
        series = text(read(entity, "getSeriesName"));
        season = nonNegativeInt(read(entity, "getSeasonNumber"));
        episode = positiveInt(read(entity, "getEpisodeNumber"));
        album = season != null && episode != null ? "Season " + season + " · Episode " + episode : null;
        mediaId = firstText(read(entity, "getContentId"), read(entity, "getId"));
        boolean live = Boolean.TRUE.equals(read(entity, "isLiveContent"));
        Object seconds = read(entity, "getDurationSeconds");
        long duration = seconds instanceof Number ? ((Number) seconds).longValue() : 0;
        durationMs = !live && duration > 0 && duration <= Long.MAX_VALUE / 1000 ? duration * 1000 : 0;
        String artwork = artwork(read(entity, "getArtwork"));
        artworkUrl = artwork != null ? artwork : artwork(read(entity, "getSeriesArtwork"));
        contentUrl = contentUrl(text(read(entity, "getUrl")));
        contentType = live ? "live" : series != null ? "episode" : "video";
    }

    static HuluMetadata fromEntity(Object entity) {
        return entity == null ? null : new HuluMetadata(entity);
    }

    private static Object read(Object source, String getter) {
        if (source == null) return null;
        try {
            return source.getClass().getMethod(getter).invoke(source);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        if (!(value instanceof String)) return null;
        String result = ((String) value).trim();
        return result.isEmpty() || result.length() > 4096 ? null : result;
    }

    private static String firstText(Object first, Object second) {
        String value = text(first);
        return value != null ? value : text(second);
    }

    private static Integer nonNegativeInt(Object value) {
        if (!(value instanceof Number)) return null;
        long number = ((Number) value).longValue();
        return number >= 0 && number <= Integer.MAX_VALUE ? (int) number : null;
    }

    private static Integer positiveInt(Object value) {
        Integer number = nonNegativeInt(value);
        return number != null && number > 0 ? number : null;
    }

    private static String artwork(Object value) {
        if (!(value instanceof Map)) return null;
        Map<?, ?> entries = (Map<?, ?>) value;
        // Prefer actual episode/program art, rather than transparent title treatments or logos.
        for (String key : new String[]{"video.horizontal.hero", "program.tile", "program.horizontal", "program.vertical"}) {
            String url = artworkUrl(text(read(entries.get(key), "getPath")));
            if (url != null) return url;
        }
        return null;
    }

    static String artworkUrl(String value) {
        URI uri = publicHuluUri(value, 300);
        if (uri == null || uri.getRawFragment() != null) return null;
        String query = uri.getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                String key = pair.split("=", 2)[0].toLowerCase(Locale.ROOT);
                if (!key.equals("operations")) return null;
            }
        }
        return value;
    }

    static String contentUrl(String value) {
        URI uri = publicHuluUri(value, 256);
        if (uri == null || !(uri.getHost().equalsIgnoreCase("hulu.com") || uri.getHost().equalsIgnoreCase("www.hulu.com"))) return null;
        // Publish only a normal public watch page, never API, playlist, or license URLs.
        if (uri.getPath() == null || !uri.getPath().matches("/watch/[A-Za-z0-9-]+/?")) return null;
        return "https://www.hulu.com" + uri.getPath();
    }

    private static URI publicHuluUri(String value, int limit) {
        if (value == null || value.length() > limit) return null;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null || uri.getPort() != -1) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (!(host.equals("hulu.com") || host.endsWith(".hulu.com") || host.equals("assetshuluimcom-a.akamaihd.net"))) return null;
            return uri;
        } catch (Exception ignored) {
            return null;
        }
    }
}
