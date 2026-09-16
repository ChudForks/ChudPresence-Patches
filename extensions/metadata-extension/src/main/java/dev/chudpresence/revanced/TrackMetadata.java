package dev.chudpresence.revanced;

final class TrackMetadata {
    final String album;
    final String albumArtist;
    final int year;
    final String artworkUrl;

    TrackMetadata(String album, String albumArtist, int year, String artworkUrl) {
        this.album = album;
        this.albumArtist = albumArtist;
        this.year = year;
        this.artworkUrl = artworkUrl;
    }

    boolean isEmpty() {
        return album == null && albumArtist == null && year == 0 && artworkUrl == null;
    }
}
