# ChudPresence ReVanced patch

`Enrich media metadata` patches YouTube Music's platform `MediaSession.setMetadata` call.
It keeps the original update synchronous, resolves the current song's structured YouTube
Music metadata on one background thread, then republishes the metadata with these Android
media keys when available:

- `android.media.metadata.ALBUM`
- `android.media.metadata.ALBUM_ARTIST`
- `android.media.metadata.YEAR`

This makes the album visible to ChudPresence and to other Android media-session consumers.
When ChudPresence receives it, the Android activity card shows the album on its own row and
an enriched-metadata marker. Discord only provides two visible activity text rows, so the
artist stays on the second row and the album is used as the cover-art label. Unpatched clients
remain supported and simply omit those album details.
The lookup is best-effort: a network/API failure leaves YouTube Music's original metadata
untouched. Results are cached in memory and late results are never applied to a newer track.

## Build

The project uses the ReVanced patches Gradle plugin. Authenticate GitHub CLI with the
`read:packages` scope, then expose that credential only to the current build process:

```powershell
gh auth refresh -h github.com -s read:packages
$env:ORG_GRADLE_PROJECT_githubPackagesUsername = gh api user --jq .login
$env:ORG_GRADLE_PROJECT_githubPackagesPassword = gh auth token
Set-Location revanced
.\gradlew.bat :patches:buildAndroid
```

The patch bundle is written below `patches/build/libs`. Use that bundle with ReVanced CLI
or a manager that supports importing third-party patch bundles, and select
`Enrich media metadata` when patching an original YouTube Music APK.

## Verify on a device

Start a song in the patched YouTube Music app and inspect the active session:

```text
adb shell dumpsys media_session
```

After the initial metadata update and background lookup, the session should contain an
`android.media.metadata.ALBUM` value. ChudPresence reads that key directly; no private IPC,
broadcast receiver, or shared signing key is required.

## Notes

- Public catalog songs are resolved through YouTube Music's WEB_REMIX `next` endpoint.
  If the media session omits the video ID, the extension falls back to a title/artist search.
- Private uploads may not resolve without account authentication; playback is unaffected.
- The patch deliberately targets the platform API call instead of obfuscated class names,
  so it is less sensitive to YouTube Music releases. APK verification is still required.
