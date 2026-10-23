# ChudPresence ReVanced metadata patches

The custom bundle adds links and artwork to Android media sessions for:

- YouTube Music **8.40.54**: `Enrich media metadata`.
- YouTube **20.40.45**: `Expose YouTube media metadata`.

Both patches preserve the original synchronous metadata update and enrich it off the
playback thread. They expose the current playing ID, not a title-search guess.
The Music patch hooks the playback-stage video-details object to recover the song ID
that the stock media session omits. These accessors are version-specific, so compatibility
is pinned to the APK verified above. YouTube hooks both foreground and background
playing-ID updates and observes player parameters to distinguish Shorts from prefetch.

Public Music album/artist information is resolved through WEB_REMIX `next` for the exact
ID. Public YouTube creator information is resolved through oEmbed for the exact ID.
Lookups are cached, failures retry after a delay when metadata updates again, and late
results cannot overwrite a newer track. For YouTube, the title and creator must match the
current media session before attaching a captured ID resolved from oEmbed. Playback is
unaffected by a network failure. Public lookups can fail for private, unavailable or
restricted items, and Shorts require an active media session from the player.

## Media-session contract

Besides standard ALBUM, ALBUM_ARTIST, YEAR and ALBUM_ART_URI keys, the extension adds:

| Key | Value |
| --- | --- |
| `dev.chudpresence.metadata.VERSION` | Contract version `1` (long) |
| `dev.chudpresence.metadata.VIDEO_ID` | Exact playing song/video ID |
| `dev.chudpresence.metadata.CONTENT_URL` | YouTube Music song or YouTube video/Short URL |
| `dev.chudpresence.metadata.CONTENT_TYPE` | `song`, `video`, or `short` |
| `dev.chudpresence.metadata.ARTIST_URL` | Music artist page or YouTube creator channel |
| `dev.chudpresence.metadata.ALBUM_URL` | Music album browse URL, when available |

ChudPresence 1.1 reads these keys in both Local and Server mode. Artist is the default
second line; the app offers an album option for Music. Artwork links to the album with
an artist-page fallback. Missing destinations are omitted. Unpatched apps still publish
whatever standard metadata they expose.

## Build on Windows

Put the stock, standalone APKs in `revanced/install`, then run from the repository root:

```powershell
.\Build-ReVanced-YouTube-Music.bat
.\Build-ReVanced-YouTube.bat
```

The wrappers use `Build-PatchedApp.ps1`, ReVanced CLI 6.0.0, the locally built upstream
patch bundle, and the custom bundle. They apply the compatible upstream defaults plus
the selected metadata patch. GitHub CLI authentication with `read:packages` is used for
build dependencies; an already populated Gradle cache can be used offline. JDK 21 and
the Android SDK are required. Set `REVANCED_PATCHES_RVP` to override the upstream bundle.

YouTube builds automatically include the merged upstream quality-selection fix from
[ReVanced MR !6983](https://gitlab.com/ReVanced/revanced-patches/-/merge_requests/6983).
It restores the available AVC resolutions when a response offers only one VP9 quality.
The backport, build provenance and regression checks are documented in
[`upstream-fixes/README.md`](upstream-fixes/README.md).

Outputs (existing outputs are backed up before rebuilding):

- `install/youtube-music-revanced-chudpresence-metadata.apk`
- `install/youtube-revanced-chudpresence-metadata.apk`
- `patches/build/libs/patches-0.2.0.rvp`

The existing Music signing keystore is reused for both apps. Keep it: future updates
must use the same signing identity to preserve app data. Stock APKs and prior builds
remain in place. Optional paths can be supplied to the shared script:

```powershell
.\revanced\Build-PatchedApp.ps1 -App youtube -InputApk C:\path\youtube.apk -OutputApk C:\path\patched.apk
```

To build the bundle and run parser tests without patching an APK:

```powershell
cd revanced
$env:ORG_GRADLE_PROJECT_githubPackagesUsername = gh api user --jq .login
$env:ORG_GRADLE_PROJECT_githubPackagesPassword = gh auth token
.\gradlew.bat :patches:buildAndroid :extensions:metadata-extension:testDebugUnitTest
```

## Verify

Install the updated player APKs and ChudPresence 1.1 on the phone, retaining the same
signing keys and GmsCore setup as the previous Music installation. Start reporting and
check presence from a second Discord account (Discord hides your own activity buttons).
Verify song/video changes, Shorts, live streams, paused playback, artist/album selection,
and every title, second-line, artwork and button destination. Server mode also needs the
updated relay and Windows worker; Local mode needs only the Android apps.
