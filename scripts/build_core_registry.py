#!/usr/bin/env python3
"""
GodotDroid Core Registry Builder (scripts/build_core_registry.py)
Automates the extraction, packaging, and cryptographic verification of Godot Android
runtime cores (libgodot_android.so + godot-lib.dex) across Godot 3.x and 4.x releases.

Produces standalone core_<version>.zip packages and a unified cores_manifest.json
ready for deployment to GitHub Releases or self-hosted CDN registries.
"""

import argparse
import hashlib
import io
import json
import os
import re
import sys
import urllib.request
import zipfile
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# Supported default core versions matrix
DEFAULT_VERSIONS = [
    "4.6-dev1",
    "4.5-stable",
    "4.4-stable",
    "4.3-stable",
    "4.2.2-stable",
    "4.1.3-stable",
    "4.0.4-stable",
    "3.6-stable",
    "3.5.3-stable",
    "3.4.5-stable",
    "3.3.4-stable",
    "3.2.3-stable"
]

GODOT_RELEASE_URL_TEMPLATE = "https://github.com/godotengine/godot/releases/download/{version}/Godot_v{version}_export_templates.tpz"
GODOT_TUXFAMILY_URL_TEMPLATE = "https://downloads.tuxfamily.org/godotengine/{folder}/Godot_v{version}_export_templates.tpz"

def calculate_sha256(filepath: Path) -> str:
    """Calculates SHA-256 checksum of a file."""
    sha256 = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            sha256.update(chunk)
    return sha256.hexdigest()

def calculate_bytes_sha256(data: bytes) -> str:
    """Calculates SHA-256 checksum of in-memory bytes."""
    return hashlib.sha256(data).hexdigest()

def parse_version_key(version_str: str) -> Tuple[str, str, int]:
    """
    Extracts version key (e.g. '4.3'), engine version (e.g. '4.3.0'), and engine major.
    Example: '4.3-stable' -> ('4.3', '4.3.0', 4)
             '4.2.2-stable' -> ('4.2', '4.2.2', 4)
             '3.5.3-stable' -> ('3.5', '3.5.3', 3)
    """
    cleaned = version_str.replace("-stable", "").replace("-dev1", "")
    parts = cleaned.split(".")
    major = int(parts[0])
    minor = parts[1] if len(parts) > 1 else "0"
    patch = parts[2] if len(parts) > 2 else "0"

    version_key = f"{major}.{minor}"
    engine_version = f"{major}.{minor}.{patch}"
    return version_key, engine_version, major

def download_file(url: str, dest_path: Path) -> bool:
    """Downloads a file with a visual progress bar."""
    print(f"[*] Downloading {url} -> {dest_path.name}...")
    try:
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "GodotDroid-Core-Builder/1.0"}
        )
        with urllib.request.urlopen(req) as response, open(dest_path, "wb") as out_file:
            total_size = int(response.headers.get("content-length", 0))
            downloaded = 0
            chunk_size = 1024 * 64

            while True:
                chunk = response.read(chunk_size)
                if not chunk:
                    break
                out_file.write(chunk)
                downloaded += len(chunk)
                if total_size > 0:
                    percent = (downloaded / total_size) * 100
                    print(f"\r    [{percent:5.1f}%] {downloaded // (1024*1024)}MB / {total_size // (1024*1024)}MB", end="", flush=True)
            print()
        return True
    except Exception as e:
        print(f"\n[-] Failed to download from {url}: {e}")
        if dest_path.exists():
            dest_path.unlink()
        return False

def extract_core_from_archive(archive_path: Path) -> Optional[Tuple[Dict[str, bytes], bytes]]:
    """
    Extracts arm64-v8a .so libraries and classes.dex from a .tpz, .apk, or .zip archive.
    Returns: (so_files_dict, dex_bytes) or None if missing required core files.
    """
    so_files: Dict[str, bytes] = {}
    dex_bytes: Optional[bytes] = None

    try:
        with zipfile.ZipFile(archive_path, 'r') as zf:
            namelist = zf.namelist()

            # Check if this is a .tpz containing android_release.apk or nested apk
            nested_apk_name = None
            for name in namelist:
                lower = name.lower()
                if "android_release.apk" in lower or lower.endswith("android_release.apk"):
                    nested_apk_name = name
                    break
                elif "android_debug.apk" in lower and not nested_apk_name:
                    nested_apk_name = name

            if nested_apk_name:
                print(f"    -> Found nested Android APK: {nested_apk_name}")
                nested_apk_bytes = zf.read(nested_apk_name)
                with zipfile.ZipFile(io.BytesIO(nested_apk_bytes), 'r') as apk_zf:
                    for apk_entry in apk_zf.namelist():
                        apk_lower = apk_entry.lower()
                        if "lib/arm64-v8a/" in apk_lower and apk_lower.endswith(".so"):
                            lib_name = os.path.basename(apk_entry)
                            so_files[lib_name] = apk_zf.read(apk_entry)
                        elif apk_lower == "classes.dex" or apk_lower.endswith("/classes.dex"):
                            if not dex_bytes:
                                dex_bytes = apk_zf.read(apk_entry)
            else:
                # Direct APK or unpacked archive
                for entry in namelist:
                    entry_lower = entry.lower()
                    if "lib/arm64-v8a/" in entry_lower and entry_lower.endswith(".so"):
                        lib_name = os.path.basename(entry)
                        so_files[lib_name] = zf.read(entry)
                    elif entry_lower == "classes.dex" or entry_lower.endswith("/classes.dex") or entry_lower.endswith("godot-lib.dex"):
                        if not dex_bytes:
                            dex_bytes = zf.read(entry)

    except Exception as e:
        print(f"[-] Error reading archive {archive_path.name}: {e}")
        return None

    # Validate that at least libgodot_android.so and dex were found
    has_godot_so = any("libgodot_android.so" in k or "godot" in k for k in so_files.keys())
    if not has_godot_so or not dex_bytes:
        print(f"[-] Missing required core components in {archive_path.name} (found .so: {list(so_files.keys())}, has_dex: {dex_bytes is not None})")
        return None

    return so_files, dex_bytes

def package_core_zip(
    output_zip_path: Path,
    so_files: Dict[str, bytes],
    dex_bytes: bytes,
    version_key: str,
    engine_version: str
) -> int:
    """
    Creates core_<version>.zip containing libgodot_android.so, companion .so files,
    godot-lib.dex, and core_manifest.json.
    """
    with zipfile.ZipFile(output_zip_path, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
        # Write .so files
        for lib_name, data in so_files.items():
            zf.writestr(lib_name, data)

        # Write godot-lib.dex
        zf.writestr("godot-lib.dex", dex_bytes)

        # Write internal core_manifest.json
        manifest = {
            "versionKey": version_key,
            "engineVersion": engine_version,
            "sourceType": "CORE_REGISTRY_BUILDER",
            "packagedTimestamp": 1729000000000,
            "libraries": list(so_files.keys())
        }
        zf.writestr("core_manifest.json", json.dumps(manifest, indent=2))

    return output_zip_path.stat().st_size

def build_cores(
    versions: List[str],
    output_dir: Path,
    local_dir: Optional[Path],
    enable_download: bool,
    repo: str,
    tag: str
) -> List[Dict]:
    """Processes all specified versions and outputs cores_manifest.json."""
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest_entries = []

    print(f"==================================================")
    print(f"  GodotDroid Core Registry Builder")
    print(f"  Target Versions: {len(versions)}")
    print(f"  Output Directory: {output_dir.resolve()}")
    print(f"==================================================")

    for ver in versions:
        version_key, engine_version, major = parse_version_key(ver)
        core_zip_name = f"core_{version_key}.zip"
        target_core_zip = output_dir / core_zip_name

        print(f"\n[+] Processing Godot {ver} (Key: {version_key}, Major: {major})...")

        # 1. Search locally
        archive_path: Optional[Path] = None
        if local_dir and local_dir.is_dir():
            for f in local_dir.iterdir():
                if f.is_file() and (f.suffix in [".tpz", ".apk", ".zip"]):
                    if ver in f.name or version_key in f.name:
                        archive_path = f
                        print(f"    Found local template: {f.name}")
                        break

        # 2. Download if not found
        temp_download: Optional[Path] = None
        if not archive_path and enable_download:
            temp_download = output_dir / f"Godot_v{ver}_export_templates.tpz"
            url = GODOT_RELEASE_URL_TEMPLATE.format(version=ver)
            if not download_file(url, temp_download):
                folder = ver.split("-")[0]
                fallback_url = GODOT_TUXFAMILY_URL_TEMPLATE.format(folder=folder, version=ver)
                if not download_file(fallback_url, temp_download):
                    print(f"[-] Could not acquire templates for {ver}. Skipping.")
                    continue
            archive_path = temp_download

        if not archive_path or not archive_path.exists():
            print(f"[-] No template archive found for {ver}. Skipping.")
            continue

        # 3. Extract core files
        extracted = extract_core_from_archive(archive_path)
        if not extracted:
            print(f"[-] Extraction failed for {ver}.")
            if temp_download and temp_download.exists():
                temp_download.unlink()
            continue

        so_files, dex_bytes = extracted
        print(f"    Extracted {len(so_files)} native library(ies) and classes.dex ({len(dex_bytes)} bytes)")

        # 4. Package core zip
        size_bytes = package_core_zip(
            output_zip_path=target_core_zip,
            so_files=so_files,
            dex_bytes=dex_bytes,
            version_key=version_key,
            engine_version=engine_version
        )

        sha256_hash = calculate_sha256(target_core_zip)
        print(f"    [OK] Packaged {core_zip_name} ({size_bytes // 1024} KB)")
        print(f"    SHA-256: {sha256_hash}")

        # Clean up temporary download to save space
        if temp_download and temp_download.exists():
            temp_download.unlink()

        # Add manifest entry
        display_name = f"Godot {version_key} {'(GLES3 / GLES2)' if major == 3 else 'Stable'}"
        download_url = f"https://github.com/{repo}/releases/download/{tag}/{core_zip_name}"

        manifest_entries.append({
            "versionKey": version_key,
            "displayName": display_name,
            "engineVersion": engine_version,
            "engineMajor": major,
            "fileName": core_zip_name,
            "sha256": sha256_hash,
            "sizeBytes": size_bytes,
            "downloadUrl": download_url,
            "isRecommended": (version_key == "4.3" or version_key == "3.6")
        })

    # Sort manifest entries descending by versionKey
    manifest_entries.sort(key=lambda x: x["versionKey"], reverse=True)

    manifest_output_path = output_dir / "cores_manifest.json"
    with open(manifest_output_path, "w", encoding="utf-8") as f:
        json.dump(manifest_entries, f, indent=2)

    print(f"\n==================================================")
    print(f"  Registry Build Complete!")
    print(f"  Manifest: {manifest_output_path.resolve()}")
    print(f"  Total Cores Built: {len(manifest_entries)}")
    print(f"==================================================")
    for entry in manifest_entries:
        print(f"  - [{entry['versionKey']}] {entry['fileName']} ({entry['sizeBytes'] // (1024*1024)} MB) -> {entry['sha256'][:16]}...")

    return manifest_entries

def main():
    parser = argparse.ArgumentParser(
        description="GodotDroid Core Registry Builder: Extract and package Godot Android runtime cores."
    )
    parser.add_argument(
        "--versions",
        nargs="+",
        default=DEFAULT_VERSIONS,
        help="Space-separated list of Godot versions (e.g. 4.3-stable 3.5.3-stable)"
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("./core_release"),
        help="Target directory for output core zips and cores_manifest.json"
    )
    parser.add_argument(
        "--local-tpz",
        type=Path,
        default=None,
        help="Path to local directory or file containing .tpz / .apk export templates"
    )
    parser.add_argument(
        "--no-download",
        action="store_true",
        help="Disable automatic downloads from official Godot repositories"
    )
    parser.add_argument(
        "--repo",
        type=str,
        default="GodotDroid/GodotDroid",
        help="GitHub repository path for download URLs (owner/repo)"
    )
    parser.add_argument(
        "--tag",
        type=str,
        default="cores-v1.0",
        help="Release tag for download URLs"
    )

    args = parser.parse_args()

    build_cores(
        versions=args.versions,
        output_dir=args.output_dir,
        local_dir=args.local_tpz,
        enable_download=not args.no_download,
        repo=args.repo,
        tag=args.tag
    )

if __name__ == "__main__":
    main()
