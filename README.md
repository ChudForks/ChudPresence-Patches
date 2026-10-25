# ChudPresence ReVanced metadata patches

This directory is a standalone Git repository nested in the ChudPresence Mobile
workspace. Its history is preserved from the former `revanced/` directory in the
parent repository. APKs, signing material, and generated build outputs stay local
and are ignored by this repository.

The custom bundle adds links and artwork to Android media sessions for:

- Hulu **6.34.0+18552420-google**: `Expose Hulu media metadata` ([build and validation notes](HULU.md)).
- YouTube Music **8.40.54**: `Enrich media metadata`.
- YouTube **20.40.45**: `Expose YouTube media metadata`.

The YouTube patches preserve the original synchronous metadata update and enrich it off the
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

Put the stock, standalone APKs in `install`, then run these commands from this
repository's root:

```powershell
.\Build-PatchedApp.ps1 -App youtube_music
.\Build-PatchedApp.ps1 -App youtube
```

The shared script uses ReVanced CLI 6.0.0, the locally built upstream patch bundle,
and the custom bundle. It applies the compatible upstream defaults plus
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
- `patches/build/libs/patches-0.3.1.rvp`

For a Hulu-only bundle, run `./Build-HuluPatch.ps1` from this repository root.
It builds and tests the extensions, then packages `patches/build/libs/hulu-metadata-0.3.1.rvp`
with only the Hulu patch, its metadata classes, and required generated API helpers. It does not install an
APK or modify ChudPresence Mobile.

The existing Music signing keystore is reused for both apps. Keep it: future updates
must use the same signing identity to preserve app data. Stock APKs and prior builds
remain in place. From the parent workspace root, optional paths can be supplied to the shared script:

```powershell
.\revanced\Build-PatchedApp.ps1 -App youtube -InputApk C:\path\youtube.apk -OutputApk C:\path\patched.apk
```

To build the bundle and run parser tests without patching an APK from the parent workspace root:

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
