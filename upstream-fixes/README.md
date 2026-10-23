# YouTube quality-selection fix

`youtube-quality-selection-6983.patch` is the four-file diff from the merged upstream
[ReVanced MR !6983](https://gitlab.com/ReVanced/revanced-patches/-/merge_requests/6983).
Upstream merge commit: `0558357345049b9fbaae43fdd0793f9bbf8ab1b2` (August 28, 2026).
It is applied to our existing `015fe10a` source snapshot in an isolated build folder.
The upstream source checkout and its original bundle are preserved.

When a stream response contains only one VP9 quality and several AVC qualities,
YouTube can select the limited VP9 set and hide the other resolutions. This change
uses the complete AVC set in that case, preserving audio and other streaming fields.
YouTube opts in; Music keeps the original behavior through the two-argument API.

`Build-ReVanced-YouTube.bat` automatically builds/uses this patched upstream bundle,
then adds our metadata patch and signs the APK with the existing key. Music continues
to use the original upstream bundle. An explicit `REVANCED_PATCHES_RVP` overrides
this selection. Published bundle hashes and upstream provenance are recorded in
`revanced/install/youtube-quality-fix/provenance.json`.

To build the upstream bundle independently, run `Build-YouTubeQualityBundle.ps1`.
`Test-YouTubeQualityFix.ps1` tests the compiled production selector using generated
protobuf messages. Android initialization and logging are replaced only in the test
process. Cases cover the incomplete VP9 ladder, preservation of complete VP9, audio,
progressive formats, other fields, duplicate quality labels and missing labels.

The resulting APK still requires playback verification on the phone. This backport
addresses the quality-selection defect; it does not establish that every possible
network or stream-client loading problem is resolved.
