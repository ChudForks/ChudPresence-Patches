package dev.chudpresence.revanced;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetadataLinksTest {
    private JSONObject queueItem(String videoId) throws Exception {
        return new JSONObject("{\"playlistPanelVideoRenderer\":{\"videoId\":\"" + videoId + "\","
            + "\"title\":{\"runs\":[{\"text\":\"A song\"}]},\"longBylineText\":{\"runs\":["
            + "{\"text\":\"An Artist\",\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"UCartist\","
            + "\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{\"pageType\":\"MUSIC_PAGE_TYPE_ARTIST\"}}}}},"
            + "{\"text\":\"An Album\",\"navigationEndpoint\":{\"browseEndpoint\":{\"browseId\":\"MPREalbum\","
            + "\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{\"pageType\":\"MUSIC_PAGE_TYPE_ALBUM\"}}}}}]}}}");
    }

    @Test public void preservesExactSongArtistAndAlbumDestinations() throws Exception {
        TrackMetadata result = YouTubeMusicMetadataClient.findTrack(queueItem("abcdefghijk"), "abcdefghijk", "A song", "An Artist");
        assertNotNull(result);
        assertEquals("abcdefghijk", result.videoId);
        assertEquals("https://music.youtube.com/browse/UCartist", result.artistUrl);
        assertEquals("https://music.youtube.com/browse/MPREalbum", result.albumUrl);
    }

    @Test public void sameTitleCannotOverrideKnownDifferentVideoId() throws Exception {
        assertNull(YouTubeMusicMetadataClient.findTrack(queueItem("differentID"), "abcdefghijk", "A song"));
    }

    @Test public void searchFallbackRequiresTheExpectedArtist() throws Exception {
        assertNull(YouTubeMusicMetadataClient.findTrack(queueItem("abcdefghijk"), null, "A song", "Someone Else"));
        assertNotNull(YouTubeMusicMetadataClient.findTrack(queueItem("abcdefghijk"), null, "A song", "An Artist"));
    }

    @Test public void youtubeUsesExactIdAndChannelFromOembed() throws Exception {
        TrackMetadata result = YouTubeMetadataClient.parse(new JSONObject("{\"title\":\"A video\",\"author_name\":\"Creator\",\"author_url\":\"https://www.youtube.com/channel/UCcreator\"}"), "abcdefghijk");
        assertEquals("abcdefghijk", result.videoId);
        assertEquals("https://www.youtube.com/channel/UCcreator", result.artistUrl);
        assertEquals("Creator", result.artist);
    }

    @Test public void youtubeRejectsForeignAuthorUrls() throws Exception {
        TrackMetadata result = YouTubeMetadataClient.parse(new JSONObject("{\"author_url\":\"https://www.youtube.com.evil.example/channel/UCcreator\"}"), "abcdefghijk");
        assertNull(result.artistUrl);
    }
}
