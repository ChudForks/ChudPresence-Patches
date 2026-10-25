package dev.chudpresence.revanced;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class HuluMetadataTest {
    public static final class Art {
        private final String path;
        Art(String path) { this.path = path; }
        public String getPath() { return path; }
    }

    public static class Movie {
        public String getName() { return "  A movie  "; }
        public String getContentId() { return "content-id"; }
        public String getId() { return "entity-id"; }
        public Integer getDurationSeconds() { return 1500; }
        public boolean isLiveContent() { return false; }
        public String getUrl() { return "https://www.hulu.com/watch/content-id?utm_source=share"; }
        public Map<String, Art> getArtwork() {
            return Collections.singletonMap("program.tile", new Art("https://img.hulu.com/user/v3/artwork/movie"));
        }
        public String getLicenseUrl() { throw new AssertionError("Must never read license data"); }
        public String getToken() { throw new AssertionError("Must never read account data"); }
    }

    public static final class Episode extends Movie {
        public String getName() { return "An episode"; }
        public String getSeriesName() { return "A series"; }
        public int getSeasonNumber() { return 2; }
        public int getEpisodeNumber() { return 3; }
    }

    @Test public void publishesTheCurrentEpisodesDescriptiveFields() {
        HuluMetadata item = HuluMetadata.fromEntity(new Episode());
        assertEquals("An episode", item.title);
        assertEquals("A series", item.series);
        assertEquals("Season 2 · Episode 3", item.album);
        assertEquals(Integer.valueOf(2), item.season);
        assertEquals(Integer.valueOf(3), item.episode);
        assertEquals(1_500_000L, item.durationMs);
        assertEquals("content-id", item.mediaId);
        assertEquals("https://img.hulu.com/user/v3/artwork/movie", item.artworkUrl);
        assertEquals("https://www.hulu.com/watch/content-id", item.contentUrl);
        assertEquals("episode", item.contentType);
    }

    @Test public void changingFromEpisodeToMovieDoesNotCarryOldSeriesOrEpisodeNumbers() {
        HuluMetadata.fromEntity(new Episode());
        HuluMetadata movie = HuluMetadata.fromEntity(new Movie());
        assertEquals("A movie", movie.title);
        assertNull(movie.series);
        assertNull(movie.album);
        assertNull(movie.season);
        assertNull(movie.episode);
        assertEquals("video", movie.contentType);
    }

    @Test public void absentEntityAndBrokenGettersDoNotInterruptPlayback() {
        assertNull(HuluMetadata.fromEntity(null));
        HuluMetadata item = HuluMetadata.fromEntity(new Movie() {
            @Override public String getName() { throw new IllegalStateException("Unavailable"); }
            @Override public Integer getDurationSeconds() { return -1; }
            @Override public Map<String, Art> getArtwork() { throw new IllegalStateException("Unavailable"); }
        });
        assertNull(item.title);
        assertNull(item.artworkUrl);
        assertEquals(0, item.durationMs);
    }

    @Test public void liveItemsDoNotPublishAFiniteEpisodeDuration() {
        HuluMetadata live = HuluMetadata.fromEntity(new Movie() {
            @Override public boolean isLiveContent() { return true; }
        });
        assertEquals(0, live.durationMs);
        assertEquals("live", live.contentType);
    }

    @Test public void artworkPrefersEpisodeImagesAndFallsBackToSeries() {
        HuluMetadata item = HuluMetadata.fromEntity(new Movie() {
            @Override public Map<String, Art> getArtwork() {
                Map<String, Art> images = new LinkedHashMap<>();
                images.put("title.treatment.stacked", new Art("https://img.hulu.com/logo"));
                images.put("program.tile", new Art("content://private/cover"));
                return images;
            }
            public Map<String, Art> getSeriesArtwork() {
                return Collections.singletonMap("program.tile", new Art("https://img.hulu.com/series"));
            }
        });
        assertEquals("https://img.hulu.com/series", item.artworkUrl);
    }

    @Test public void unsafeOrSignedArtworkLocationsAreNotExposed() {
        for (String url : new String[]{"http://img.hulu.com/image", "file:///private/image", "content://private/image",
            "https://img.hulu.com.evil.example/image", "https://user:password@img.hulu.com/image",
            "https://img.hulu.com:8443/image", "https://img.hulu.com/image?token=private",
            "https://img.hulu.com/image?signature=private", "https://img.hulu.com/image#private",
            "https://img.hulu.com/image?%74oken=private"}) {
            assertNull(url, HuluMetadata.artworkUrl(url));
        }
        assertEquals("https://img.hulu.com/image?operations=resize", HuluMetadata.artworkUrl("https://img.hulu.com/image?operations=resize"));
    }

    @Test public void watchLinksCannotExposeApiOrLicenseLocations() {
        for (String url : new String[]{"https://play.hulu.com/license", "https://www.hulu.com/api/items",
            "https://evil.example/watch/id", "https://www.hulu.com.evil.example/watch/id", "file:///watch/id"}) {
            assertNull(url, HuluMetadata.contentUrl(url));
        }
        assertEquals("https://www.hulu.com/watch/id", HuluMetadata.contentUrl("https://hulu.com/watch/id?token=private#tracking"));
    }
}
