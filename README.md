# ChudPresence Patches

Custom [ReVanced](https://revanced.app) patches that expose richer Android
media-session metadata for ChudPresence and other media-session consumers.

One versioned .rvp bundle contains all three custom patches:

| App | Compatible stock version | Patch | Published metadata |
| --- | --- | --- | --- |
| Hulu (com.hulu.plus) | 6.34.0+18552420-google | **Expose Hulu media metadata** | Title, series, season/episode, duration, content ID, public artwork and an available public watch URL. |
| YouTube Music (com.google.android.apps.youtube.music) | 8.40.54 | **Enrich media metadata** | Exact playing song ID, song/artist/album links, album metadata and public artwork. |
| YouTube (com.google.android.youtube) | 20.40.45 | **Expose YouTube media metadata** | Playing video ID, creator/channel link, public artwork and video/Shorts URL. |

The hooks are version-specific. Use the listed stock versions. The YouTube and
Music patches publish the original metadata immediately and enrich it off the
playback thread. Public lookups are cached; stale results cannot overwrite a
newer item, and lookup failures leave playback unaffected. Hulu reads metadata
already loaded by its player without network requests.

## Add to ReVanced Manager

Use this stable custom patch source URL:

```text
https://raw.githubusercontent.com/ChudForks/ChudPresence-Patches/main/patches.json
```

1. In ReVanced Manager, open **Patches**, tap the edit button, then **+**.
2. Choose **Enter URL** and paste the source URL above.
3. Select a compatible stock APK obtained separately and choose its metadata patch.
4. Keep the official ReVanced source for any upstream patches you also want to apply.

The Manager source is a JSON manifest, rather than a direct .rvp link. These
steps follow the [official Manager documentation](https://github.com/ReVanced/revanced-manager/blob/main/docs/2_3_managing_patches.md).
Alternatively, download the custom bundle from [Releases](https://github.com/ChudForks/ChudPresence-Patches/releases/latest).
SHA256SUMS accompanies each bundle. Releases currently have no detached signature;
signature_download_url is null in the manifest.

This repository distributes patch source and custom patch bundles. Obtain
application APKs, ReVanced Manager/CLI, GmsCore, and official patch bundles from
their respective sources. APKs, signing keys, upstream ReVanced checkouts and
bundles, and local build caches stay outside Git and release assets.

## YouTube quality-selection source backport

[upstream-fixes/](upstream-fixes/README.md) contains a source diff for ReVanced's
upstream quality-selection fix, with provenance and local regression checks.
It restores the AVC resolution ladder when a response offers only one VP9 quality.
It modifies the upstream **Spoof video streams** implementation and is **not part
of the custom metadata bundle**. No patched upstream bundle is published here.
The optional local YouTube APK workflow can build the backport from an upstream
checkout supplied by the developer.

## Build the custom bundle

Requirements: JDK 21, Python 3.12+, Android SDK, and GitHub Packages authentication
with read:packages for ReVanced build dependencies. No application APK or
upstream patch checkout is needed to build this bundle.

Windows PowerShell, using an authenticated GitHub CLI:

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ORG_GRADLE_PROJECT_githubPackagesUsername = gh api user --jq .login
$env:ORG_GRADLE_PROJECT_githubPackagesPassword = gh auth token
.\gradlew.bat :patches:buildAndroid :extensions:metadata-extension:testDebugUnitTest
python -m unittest discover -s scripts -p 'test_*.py' -v
python scripts/publication.py audit-source
python scripts/publication.py prepare
```

On Linux/macOS, use ./gradlew with the same tasks and provide the two Gradle
credential properties through the environment. The build creates
patches/build/libs/patches-<version>.rvp. The publication script inspects its
contents and stages exactly that bundle and its checksum in dist/; that
directory must be empty before preparing another release.

For a local Hulu-only bundle, see [HULU.md](HULU.md) and Build-HuluPatch.ps1.
The current 0.3.1 Hulu bundle fixes the missing generated runtime helper in the
older 0.3.0 Hulu-only output. Public releases use the combined bundle.

### Optional local APK workflows

Build-PatchedApp.ps1 can combine a developer-supplied upstream bundle with the
custom metadata patch and a stock APK. Its install/ directory is ignored.
The Music workflow expects an upstream source/build under
install/upstream-revanced-patches/; YouTube also supports the local quality
backport. Set REVANCED_PATCHES_RVP to supply another compatible upstream bundle.
These scripts never run in the public release workflow.

## Release automation

[GitHub Actions](.github/workflows/release.yml) builds the combined custom bundle
and runs the extension and publication-policy tests on pushes and pull requests.
The build uses the workflow token with packages: read for public build dependencies.

For a new release, increase version in gradle.properties and push to main.
The workflow:

1. Audits every reachable Git commit and the current index against a patch-source
   allowlist, rejecting app binaries, upstream checkouts, credentials and signing material.
2. Builds and validates the custom bundle, including class ownership in both DEX files.
3. Uploads exactly patches-<version>.rvp and SHA256SUMS using explicit paths.
4. Publishes a GitHub Release tagged v<version> without replacing existing assets.
5. Updates patches.json from the actual release URL and publication time.

The Manager source URL stays the same across releases. The workflow can also be
run manually. Add new custom patch definitions to the publication allowlist and
update the release notes when expanding the bundle. Generated build directories
and dependency caches are never uploaded as release assets or workflow artifacts.

## Media-session contract

Besides standard ALBUM, ALBUM_ARTIST, YEAR and ALBUM_ART_URI keys, the
YouTube/Music extension adds:

| Key | Value |
| --- | --- |
| dev.chudpresence.metadata.VERSION | Contract version 1 (long) |
| dev.chudpresence.metadata.VIDEO_ID | Exact playing song/video ID |
| dev.chudpresence.metadata.CONTENT_URL | Music song or YouTube video/Short URL |
| dev.chudpresence.metadata.CONTENT_TYPE | song, video, or short |
| dev.chudpresence.metadata.ARTIST_URL | Music artist page or YouTube creator channel |
| dev.chudpresence.metadata.ALBUM_URL | Music album browse URL, when available |

Hulu additionally publishes SEASON_NUMBER (long) and EPISODE_NUMBER (string)
under the same namespace. See [HULU.md](HULU.md) for its complete behavior and
validation limits. Missing metadata or destinations are omitted.

## License

[GNU GPL version 3 or later](LICENSE). Attribution and upstream provenance are
recorded in [NOTICE](NOTICE) and [upstream-fixes/README.md](upstream-fixes/README.md).
This is an independent custom patch source.
