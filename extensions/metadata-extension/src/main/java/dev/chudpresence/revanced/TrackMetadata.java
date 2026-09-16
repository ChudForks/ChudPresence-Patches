package dev.chudpresence.revanced;

final class TrackMetadata {
    final String album;
    final String albumArtist;
    final int year;

    TrackMetadata(String album, String albumArtist, int year) {
        this.album = album;
        this.albumArtist = albumArtist;
        this.year = year;
    }

    boolean isEmpty() {
        return album == null && albumArtist == null && year == 0;
    }
}
