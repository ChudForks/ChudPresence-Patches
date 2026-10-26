"""Build a custom-only release and its ReVanced Manager manifest (stdlib only)."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from datetime import datetime
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parents[1]
PATCH_CLASSES = {
    "dev/chudpresence/patches/hulu/ExposeHuluMetadataPatchKt",
    "dev/chudpresence/patches/music/EnrichMediaMetadataPatchKt",
    "dev/chudpresence/patches/youtube/ExposeVideoMetadataPatchKt",
}
ROOT_FILES = {
    ".gitignore", ".gitattributes", "README.md", "HULU.md", "LICENSE", "NOTICE",
    "Build-HuluPatch.ps1", "Build-PatchedApp.ps1", "gradlew", "gradlew.bat",
    "gradle.properties", "settings.gradle.kts", "patches.json", "presence-services.json",
    "METADATA-CONTRACT.md",
}
BUILD_FILES = {
    "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/gradle-wrapper.jar", "patches/build.gradle.kts",
    "patches/api/patches.api", "extensions/metadata-extension/build.gradle.kts",
    "extensions/metadata-extension/src/main/AndroidManifest.xml",
}
SOURCE_ROOTS = {
    "patches/src/": {".kt", ".java"},
    "extensions/metadata-extension/src/": {".kt", ".java"},
    "upstream-fixes/": {".patch", ".md", ".ps1", ".java"},
    "verification/": {".java"},
    "scripts/": {".py"},
    ".github/workflows/": {".yml", ".yaml"},
}
SECRET_PATTERNS = (
    re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(rb"\bgithub_pat_[A-Za-z0-9_]{40,}\b"),
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate_source_path(name: str, mode: str = "100644") -> None:
    path = PurePosixPath(name)
    require(mode in {"100644", "100755"}, f"Symlink or submodule forbidden: {name}")
    require(not path.is_absolute() and ".." not in path.parts, f"Invalid path: {name}")
    require(not any(part.lower() in {"install", "build", "dist", ".git", ".gradle",
                                    "upstream-revanced-patches"} for part in path.parts),
            f"Local or generated content forbidden: {name}")
    allowed = name in ROOT_FILES or name in BUILD_FILES or any(
        name.startswith(prefix) and path.suffix in suffixes
        for prefix, suffixes in SOURCE_ROOTS.items()
    )
    require(allowed, f"File outside the patch-source allowlist: {name}")


def git(*args: str) -> bytes:
    # Per-command trust keeps this portable on checkouts copied between Windows users.
    return subprocess.check_output(
        ["git", "-c", f"safe.directory={ROOT.as_posix()}", *args], cwd=ROOT
    )


def audit_source() -> None:
    """Inspect all reachable history and the index, not ignored local app files."""
    blobs: dict[str, str] = {}
    revisions = git("rev-list", "HEAD").decode().splitlines()
    for revision in revisions:
        for record in git("ls-tree", "-rz", "--full-tree", revision).split(b"\0"):
            if not record:
                continue
            metadata, raw_name = record.split(b"\t", 1)
            mode, kind, object_id = metadata.decode().split()
            name = raw_name.decode("utf-8")
            validate_source_path(name, mode)
            require(kind == "blob", f"Non-source object: {name}")
            blobs[object_id] = name
    for record in git("ls-files", "--stage", "-z").split(b"\0"):
        if not record:
            continue
        metadata, raw_name = record.split(b"\t", 1)
        mode, object_id, stage = metadata.decode().split()
        name = raw_name.decode("utf-8")
        require(stage == "0", f"Unmerged source: {name}")
        validate_source_path(name, mode)
        blobs[object_id] = name
    for object_id, name in blobs.items():
        data = git("cat-file", "blob", object_id)
        require(len(data) <= 1_000_000, f"Unexpectedly large source file: {name}")
        if name == "gradle/wrapper/gradle-wrapper.jar":
            continue
        require(b"\0" not in data, f"Binary source file forbidden: {name}")
        require(not any(pattern.search(data) for pattern in SECRET_PATTERNS),
                f"Credential detected in source history: {name}")
    print(f"Source audit passed: {len(revisions)} commits, {len(blobs)} source blobs.")


def dex_classes(data: bytes) -> set[str]:
    require(len(data) >= 112 and data[:4] == b"dex\n" and data[7] == 0,
            "Invalid DEX header")
    require(struct.unpack_from("<I", data, 32)[0] == len(data), "Invalid DEX size")
    strings, strings_offset = struct.unpack_from("<II", data, 56)
    types, types_offset = struct.unpack_from("<II", data, 64)
    classes, classes_offset = struct.unpack_from("<II", data, 96)
    for count, offset, size in ((strings, strings_offset, 4), (types, types_offset, 4),
                                (classes, classes_offset, 32)):
        require(offset >= 112 and offset + count * size <= len(data), "Invalid DEX table")
    require(classes > 0, "DEX contains no classes")
    result = set()
    for index in range(classes):
        type_id = struct.unpack_from("<I", data, classes_offset + index * 32)[0]
        require(type_id < types, "Invalid DEX class type")
        string_id = struct.unpack_from("<I", data, types_offset + type_id * 4)[0]
        require(string_id < strings, "Invalid DEX type name")
        offset = struct.unpack_from("<I", data, strings_offset + string_id * 4)[0]
        for _ in range(5):
            require(offset < len(data), "Invalid DEX string")
            byte = data[offset]
            offset += 1
            if byte < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.find(b"\0", offset)
        require(end >= offset, "Unterminated DEX string")
        result.add(data[offset:end].decode("utf-8"))
    return result


def validate_bundle(bundle: Path) -> None:
    require(bundle.suffix == ".rvp", "Release asset must be an RVP patch bundle")
    require(bundle.stat().st_size < 10_000_000, "Unexpectedly large patch bundle")
    with zipfile.ZipFile(bundle) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate bundle entries")
        files = {name for name in names if not name.endswith("/")}
        required = {name + ".class" for name in PATCH_CLASSES}
        required |= {"META-INF/MANIFEST.MF", "classes.dex", "extensions/metadata-extension.rve"}
        require(required <= files, "Custom patch or extension missing from bundle")
        for info in archive.infolist():
            name = info.filename
            path = PurePosixPath(name)
            require(not path.is_absolute() and ".." not in path.parts and "\\" not in name,
                    f"Invalid bundle path: {name}")
            require((info.external_attr >> 16) & 0o170000 != 0o120000,
                    f"Symlink in patch bundle: {name}")
            require(info.file_size <= 10_000_000, f"Oversized bundle entry: {name}")
            if info.is_dir():
                continue
            allowed = name in required or name == "META-INF/patches.kotlin_module" or (
                name.startswith("dev/chudpresence/patches/") and name.endswith(".class")
            )
            require(allowed, f"Non-custom content in patch bundle: {name}")
        patch_dex = dex_classes(archive.read("classes.dex"))
        require(all(name.startswith("Ldev/chudpresence/patches/") for name in patch_dex),
                "Upstream or app classes found in patch DEX")
        require({"L" + name + ";" for name in PATCH_CLASSES} <= patch_dex,
                "Custom Android patch definitions missing")
        extension_dex = dex_classes(archive.read("extensions/metadata-extension.rve"))
        # The Gradle Kotlin plugin includes its standard runtime and annotations.
        runtime_prefixes = ("Ldev/chudpresence/revanced/", "Lkotlin/",
                            "Lorg/jetbrains/annotations/", "Lorg/intellij/lang/annotations/")
        require(all(name.startswith(runtime_prefixes) for name in extension_dex),
                "Upstream or app classes found in embedded extension")
        require("Ldev/chudpresence/revanced/HuluMetadataBridge;" in extension_dex and
                "Ldev/chudpresence/revanced/MetadataEnricher;" in extension_dex,
                "Custom runtime helpers missing")
    print(f"Bundle audit passed: {bundle.name}; custom patches and runtime only.")


def version() -> str:
    match = re.search(r"(?m)^version=(\d+\.\d+\.\d+)$",
                      (ROOT / "gradle.properties").read_text(encoding="utf-8"))
    require(match is not None, "Set a stable semantic version in gradle.properties")
    return match[1]


def prepare() -> None:
    release_version = version()
    bundle = ROOT / "patches/build/libs" / f"patches-{release_version}.rvp"
    validate_bundle(bundle)
    destination = ROOT / "dist"
    destination.mkdir(exist_ok=True)
    require(not any(destination.iterdir()), "Release staging directory must be empty")
    shutil.copyfile(bundle, destination / bundle.name)
    digest = hashlib.sha256(bundle.read_bytes()).hexdigest()
    (destination / "SHA256SUMS").write_text(f"{digest}  {bundle.name}\n", encoding="utf-8")
    print(f"Prepared only {bundle.name} and SHA256SUMS.")


def manifest(release: dict, repository: str) -> dict:
    require(re.fullmatch(r"[\w.-]+/[\w.-]+", repository) is not None, "Invalid repository")
    tag = release["tagName"]
    require(re.fullmatch(r"v\d+\.\d+\.\d+", tag) is not None, "Invalid release tag")
    require(not release.get("isDraft", False) and not release.get("isPrerelease", False),
            "Manager source must reference a published stable release")
    expected_name = f"patches-{tag[1:]}.rvp"
    assets = release["assets"]
    require({asset["name"] for asset in assets} == {expected_name, "SHA256SUMS"},
            "Release assets must contain only the custom bundle and checksums")
    bundle_asset = next(asset for asset in assets if asset["name"] == expected_name)
    expected_url = f"https://github.com/{repository}/releases/download/{tag}/{expected_name}"
    require(bundle_asset["url"] == expected_url, "Unexpected release download URL")
    published_at = release["publishedAt"]
    datetime.fromisoformat(published_at.replace("Z", "+00:00"))
    return {
        "version": tag,
        # Manager deserializes a LocalDateTime (UTC without a zone suffix).
        "created_at": published_at.removesuffix("Z").removesuffix("+00:00"),
        "description": release.get("body") or "ChudPresence custom media metadata patches.",
        "download_url": expected_url,
        "signature_download_url": None,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("audit-source")
    subparsers.add_parser("prepare")
    subparsers.add_parser("version")
    check = subparsers.add_parser("audit-bundle")
    check.add_argument("bundle", type=Path)
    make_manifest = subparsers.add_parser("manifest")
    make_manifest.add_argument("release", type=Path)
    make_manifest.add_argument("--repository", required=True)
    make_manifest.add_argument("--output", type=Path, default=ROOT / "patches.json")
    args = parser.parse_args()
    if args.command == "audit-source":
        audit_source()
    elif args.command == "audit-bundle":
        validate_bundle(args.bundle)
    elif args.command == "prepare":
        prepare()
    elif args.command == "version":
        print(version())
    elif args.command == "manifest":
        output = manifest(json.loads(args.release.read_text(encoding="utf-8-sig")), args.repository)
        args.output.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile, KeyError, struct.error) as error:
        print(f"Publication stopped: {error}", file=sys.stderr)
        sys.exit(1)
