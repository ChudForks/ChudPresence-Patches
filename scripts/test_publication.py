"""Regression checks for the upload boundary and Manager release metadata."""

import copy
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

import publication


def dex_fixture(names):
    """Minimal DEX tables for testing class ownership checks without app fixtures."""
    count = len(names)
    strings_offset = 112
    types_offset = strings_offset + 4 * count
    classes_offset = types_offset + 4 * count
    data = bytearray(classes_offset + 32 * count)
    data[:8] = b"dex\n039\0"
    struct.pack_into("<II", data, 56, count, strings_offset)
    struct.pack_into("<II", data, 64, count, types_offset)
    struct.pack_into("<II", data, 96, count, classes_offset)
    for index, name in enumerate(names):
        encoded = name.encode()
        struct.pack_into("<I", data, strings_offset + 4 * index, len(data))
        struct.pack_into("<I", data, types_offset + 4 * index, index)
        struct.pack_into("<I", data, classes_offset + 32 * index, index)
        size = len(encoded)
        while size >= 128:
            data.append((size & 127) | 128)
            size >>= 7
        data.append(size)
        data.extend(encoded + b"\0")
    struct.pack_into("<I", data, 32, len(data))
    return bytes(data)


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.bundle = Path(self.temp.name) / "patches-0.3.1.rvp"
        self.release = {
            "tagName": "v0.3.1", "isDraft": False, "isPrerelease": False,
            "publishedAt": "2026-10-25T23:00:00Z", "body": "Custom metadata patches.",
            "assets": [
                {"name": "patches-0.3.1.rvp", "url":
                 "https://github.com/ChudForks/ChudPresence-Patches/releases/download/v0.3.1/patches-0.3.1.rvp"},
                {"name": "SHA256SUMS", "url": "unused"},
            ],
        }

    def write_bundle(self, extra=None, extension_class=None, patch_class=None):
        with zipfile.ZipFile(self.bundle, "w") as archive:
            for name in publication.PATCH_CLASSES:
                archive.writestr(name + ".class", b"test JVM class")
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            patches = ["L" + name + ";" for name in publication.PATCH_CLASSES]
            if patch_class:
                patches.append(patch_class)
            archive.writestr("classes.dex", dex_fixture(patches))
            runtime = ["Ldev/chudpresence/revanced/HuluMetadataBridge;",
                       "Ldev/chudpresence/revanced/MetadataEnricher;", "Lkotlin/Unit;"]
            if extension_class:
                runtime.append(extension_class)
            archive.writestr("extensions/metadata-extension.rve", dex_fixture(runtime))
            if extra:
                archive.writestr(extra, b"forbidden content")

    def test_current_source_paths_are_allowed(self):
        for name in ("scripts/publication.py", ".github/workflows/release.yml",
                     "gradle/wrapper/gradle-wrapper.jar",
                     "upstream-fixes/youtube-quality-selection-6983.patch"):
            publication.validate_source_path(name)

    def test_local_apps_bundles_and_upstream_sources_are_rejected(self):
        for name in ("install/youtube.apk", "app.apk", "patches/new.rvp",
                     "patches/src/main/app.dex", "local.properties", ".env",
                     "install/upstream-revanced-patches/patches/src/main/Foo.kt"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                publication.validate_source_path(name)

    def test_symlinks_and_submodules_are_rejected(self):
        for mode in ("120000", "160000"):
            with self.assertRaises(ValueError):
                publication.validate_source_path("README.md", mode)

    def test_custom_bundle_and_its_runtime_are_allowed(self):
        self.write_bundle()
        publication.validate_bundle(self.bundle)

    def test_apk_and_embedded_upstream_bundle_are_rejected(self):
        for name in ("youtube.apk", "upstream.rvp", "../hidden.apk"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.write_bundle(extra=name)
                publication.validate_bundle(self.bundle)

    def test_app_classes_cannot_hide_in_the_patch_dex(self):
        self.write_bundle(patch_class="Lcom/google/android/youtube/Player;")
        with self.assertRaises(ValueError):
            publication.validate_bundle(self.bundle)

    def test_upstream_extension_classes_are_rejected(self):
        self.write_bundle(extension_class="Lapp/revanced/extension/youtube/Patch;")
        with self.assertRaises(ValueError):
            publication.validate_bundle(self.bundle)

    def test_truncated_dex_is_rejected(self):
        with self.assertRaises(ValueError):
            publication.dex_classes(b"dex\n039\0")

    def test_manifest_uses_the_actual_release_timestamp_and_asset(self):
        result = publication.manifest(self.release, "ChudForks/ChudPresence-Patches")
        self.assertEqual(result["version"], "v0.3.1")
        self.assertEqual(result["created_at"], "2026-10-25T23:00:00")
        self.assertEqual(result["download_url"], self.release["assets"][0]["url"])
        self.assertIsNone(result["signature_download_url"])

    def test_manifest_rejects_app_assets_and_other_download_hosts(self):
        invalid = copy.deepcopy(self.release)
        invalid["assets"].append({"name": "youtube.apk", "url": "unused"})
        with self.assertRaises(ValueError):
            publication.manifest(invalid, "ChudForks/ChudPresence-Patches")
        invalid = copy.deepcopy(self.release)
        invalid["assets"][0]["url"] = "https://example.com/patches.rvp"
        with self.assertRaises(ValueError):
            publication.manifest(invalid, "ChudForks/ChudPresence-Patches")

    def test_unpublished_releases_are_rejected(self):
        for flag in ("isDraft", "isPrerelease"):
            invalid = dict(self.release, **{flag: True})
            with self.assertRaises(ValueError):
                publication.manifest(invalid, "ChudForks/ChudPresence-Patches")


if __name__ == "__main__":
    unittest.main()
