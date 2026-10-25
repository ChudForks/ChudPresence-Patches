# Hulu media-session metadata patch

Patch: **Expose Hulu media metadata**

Compatible package/version: **com.hulu.plus / 6.34.0+18552420-google**

Hulu-only output: **patches/build/libs/hulu-metadata-0.3.1.rvp**, relative to this
`revanced` directory. Load that bundle in ReVanced Manager and select the patch above.

## Behavior

The patch adds calls at the start of `PlayerPresenter.s(String, boolean)` and
`PlayerPresenter.s0(PlayableEntity)`. These copy the already-loaded playable item's
descriptive fields into Hulu's existing Android `MediaSession`:

- Title and series as standard title/artist metadata.
- Season and episode as the album label, plus `dev.chudpresence.metadata.SEASON_NUMBER`
  (long) and `EPISODE_NUMBER` (string).
- Content/entity ID, finite duration in milliseconds, and public artwork URL when available.
- An exposed public Hulu watch page, if present, as media URI and `CONTENT_URL`.
- `CONTENT_TYPE` (`episode`, `video`, or `live`) and metadata contract `VERSION=1`.

The extension reads an explicit list of public descriptive getters. It makes no network
requests and does not read playlist, license, account, or token fields. It accepts only
HTTPS Hulu artwork locations, rejects credential-bearing URLs and signed query parameters,
and omits missing metadata. Movie transitions replace the prior episode's metadata;
live items omit finite episode durations. Getter/publisher failures are contained.

This changes only the patched Hulu player. No ChudPresence Mobile source changes are
required to build the patch. ChudPresence now consumes the season/episode keys and offers
the same season/episode profile choice as Crunchyroll. Playback, DRM, licensing, integrity checks, and quality selection
are not patched. A changed APK signing identity may still affect Hulu's acceptance of
the client; on-device playback and resolution preservation are not yet verified.

## Build

JDK 21, Android SDK, the existing ReVanced CLI 6.0.0 in `install/`, and GitHub CLI
authenticated with `read:packages` are required by the existing build configuration.
From the repository root:

```powershell
.\revanced\Build-HuluPatch.ps1
```

The script builds the standard bundle, runs extension unit tests, and extracts a
Hulu-only `.rvp` containing both JVM patch classes and Android DEX patch classes.
Its embedded extension contains `HuluMetadata`, `HuluMetadataBridge`, and their transitive
generated API helper dependencies; YouTube patches and their network clients are excluded.
`-SkipBuild` packages an existing build.

Version **0.3.0** of the Hulu-only bundle omitted D8's shared API-outline helper because
its generated name began with `MetadataEnricher`, rather than `Hulu`. This caused the
publisher to catch a missing-class error without publishing metadata. Version **0.3.1**
retains referenced extension classes transitively. Re-patch with 0.3.1 to correct an
installation made from the original Hulu-only bundle. The metadata contract is unchanged.

## Validation

The extension unit suite passes 14 tests, including 7 Hulu tests for field extraction,
movie/live handling, missing/throwing getters, artwork fallback, and URL boundaries.
ReVanced CLI lists the Hulu-only bundle with the exact package/version compatibility
and successfully applies it to the copied stock Hulu APK without forcing compatibility.

For a local APK application, supply the Hulu-only bundle with `--exclusive` and
`--enable="Expose Hulu media metadata"`, a distinct output APK, and a dedicated test
signing keystore. Do not supply the CLI installation flag when only validating a build.
The stock APK used in this session is `install/hulu-stock-6.34.0.apk`; it remains unchanged.

After building, verify a local patched APK without installing it:

```powershell
java -Xmx2g -classpath "revanced/install/revanced-cli-6.0.0-all.jar;revanced/patches/build/hulu-tools" `
  HuluPatchTools audit revanced/install/hulu-stock-6.34.0.apk revanced/install/hulu-metadata-0.3.1-validation.apk
```

The audit compares all stock classes as canonical smali, allowing equivalent DEX
encodings (string/branch width, alignment nops, and omitted default field initializers).
It requires exactly two additive metadata hooks in the existing presenter, preserves
the original bodies after those prefixes, and permits only Hulu extension classes and
their generated API-outline helpers. Every non-DEX/non-signature APK entry, including native libraries and resources,
must remain byte-identical. This static audit does not establish runtime DRM or HD success.

The main Android project's `HuluMediaSessionTest.packagedHuluExtensionPublishesRealAndroidMetadata`
additionally loads the actual packaged extension DEX on a device and publishes to a real
inactive Android media session. This catches missing runtime helper dependencies that a
bytecode-only audit does not. The corrected 0.3.1 bundle passes that check on the Samsung.
