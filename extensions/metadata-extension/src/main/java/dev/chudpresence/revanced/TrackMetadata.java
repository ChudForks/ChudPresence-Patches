package dev.chudpresence.revanced;

final class TrackMetadata {
    final String album;
    final String albumArtist;
    final int year;
    final String artworkUrl;
    final String videoId;
    final String artistUrl;
    final String albumUrl;
    final String title;
    final String artist;

    TrackMetadata(String album, String albumArtist, int year, String artworkUrl) {
        this(album, albumArtist, year, artworkUrl, null, null, null, null, null);
    }

    TrackMetadata(String album, String albumArtist, int year, String artworkUrl,
                  String videoId, String artistUrl, String albumUrl, String title, String artist) {
        this.album = album;
        this.albumArtist = albumArtist;
        this.year = year;
        this.artworkUrl = artworkUrl;
        this.videoId = videoId;
        this.artistUrl = artistUrl;
        this.albumUrl = albumUrl;
        this.title = title;
        this.artist = artist;
    }

    boolean isEmpty() {
        return album == null && albumArtist == null && year == 0 && artworkUrl == null
            && videoId == null && artistUrl == null && albumUrl == null;
    }
}
