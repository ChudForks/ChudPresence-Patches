package dev.chudpresence.revanced;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class YouTubeMusicMetadataClientTest {
    @Test
    public void parsesAlbumArtistAndYearFromMatchingQueueItem() throws Exception {
        JSONObject response = new JSONObject("{\"contents\":[{\"playlistPanelVideoRenderer\":{" +
            "\"videoId\":\"iKLU7z_xdYQ\"," +
            "\"title\":{\"runs\":[{\"text\":\"Walk On Water (feat. Beyoncé)\"}]}," +
            "\"longBylineText\":{\"runs\":[" +
            "{\"text\":\"Eminem\",\"navigationEndpoint\":{\"browseEndpoint\":{" +
            "\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{" +
            "\"pageType\":\"MUSIC_PAGE_TYPE_ARTIST\"}}}}}," +
            "{\"text\":\" • \"}," +
            "{\"text\":\"Revival\",\"navigationEndpoint\":{\"browseEndpoint\":{" +
            "\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{" +
            "\"pageType\":\"MUSIC_PAGE_TYPE_ALBUM\"}}}}}," +
            "{\"text\":\" • \"},{\"text\":\"2017\"}]}}}]}");

        TrackMetadata result = YouTubeMusicMetadataClient.findTrack(
            response, "iKLU7z_xdYQ", "Walk On Water (feat. Beyoncé)");

        assertNotNull(result);
        assertEquals("Revival", result.album);
        assertEquals("Eminem", result.albumArtist);
        assertEquals(2017, result.year);
    }

    @Test
    public void parsesAlbumFromSearchResultWhenMediaIdIsMissing() throws Exception {
        JSONObject response = new JSONObject("{\"musicResponsiveListItemRenderer\":{" +
            "\"flexColumns\":[" +
            "{\"musicResponsiveListItemFlexColumnRenderer\":{\"text\":{\"runs\":[" +
            "{\"text\":\"Walk On Water (feat. Beyoncé)\"}]}}}," +
            "{\"musicResponsiveListItemFlexColumnRenderer\":{\"text\":{\"runs\":[" +
            "{\"text\":\"Eminem\",\"navigationEndpoint\":{\"browseEndpoint\":{" +
            "\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{" +
            "\"pageType\":\"MUSIC_PAGE_TYPE_ARTIST\"}}}}}," +
            "{\"text\":\" • \"}," +
            "{\"text\":\"Revival\",\"navigationEndpoint\":{\"browseEndpoint\":{" +
            "\"browseEndpointContextSupportedConfigs\":{\"browseEndpointContextMusicConfig\":{" +
            "\"pageType\":\"MUSIC_PAGE_TYPE_ALBUM\"}}}}}]}}}]}}");

        TrackMetadata result = YouTubeMusicMetadataClient.findTrack(
            response, null, "Walk On Water (feat. Beyoncé)");

        assertNotNull(result);
        assertEquals("Revival", result.album);
        assertEquals("Eminem", result.albumArtist);
    }
}
