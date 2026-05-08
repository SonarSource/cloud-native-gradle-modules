#!/usr/bin/env python3
"""
Pre-download Equo P2 bundle pool JARs to avoid CloudFlare WARP timeout issues.

The Spotless Groovy/Java Eclipse formatters download large JARs via OkHttp with a
10-second read timeout. CloudFlare WARP traffic inspection slows downloads below
that threshold, causing builds to fail. This script pre-populates the Equo P2
bundle pool using urllib downloads (unaffected by OkHttp's timeout).

Usage:
  python3 scripts/spotless-fix-dependencies.py [project_dir]

If project_dir is omitted, uses the current directory.
Works with: differential-validation, sonar-iac-enterprise (and other repos that ise those modules).

On the first run the script downloads artifact manifests from the P2 repositories
and caches them locally. Subsequent runs use the cached manifests and only download
missing JARs (so they are fast even on a slow/WARP network).
"""

import hashlib
import base64
import json
import lzma
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import io
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Optional

BUNDLE_POOL_BASE = Path.home() / ".m2/repository/dev/equo/p2-data/bundle-pool"
GRADLE_CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
# Local cache for artifact manifests (avoids slow remote fetches on repeat runs)
MANIFEST_CACHE = Path.home() / ".cache/spotless-fix-dependencies/manifests"
MANIFEST_TTL_HOURS = 24 * 7  # re-fetch weekly
MAX_WORKERS = 8

# Directories containing build scripts (relative to project root, searched shallowly)
BUILD_SCRIPT_DIRS = [
    "build-logic/src/main/kotlin",
    "build-logic/src/main/groovy",
    "gradle/build-logic-common/gradle-modules/src/main/kotlin",
    "build-logic/common/gradle-modules/src/main/kotlin",
    ".",
]


# ---------------------------------------------------------------------------
# Equo filenameSafe: replicates OfflineCache.filenameSafe() in Java.
# Concat template for truncated URLs: "\u0001--\u0001\u0001" → first + "--" + b64 + last
# ---------------------------------------------------------------------------

def filename_safe(url: str) -> str:
    s = re.sub(r"[^a-zA-Z0-9\-+_.]", "-", url)
    s = re.sub(r"-+", "-", s)
    if len(s) <= 92:
        return s
    n = len(s) - 40
    first, middle, last = s[:40], s[40:n], s[n:]
    md5 = hashlib.md5(middle.encode("utf-8")).digest()
    b64 = base64.b64encode(md5).decode("ascii").replace("/", "-").replace("=", "-")
    return first + "--" + b64 + last


def bundle_pool_dir(p2_url: str) -> Path:
    return BUNDLE_POOL_BASE / filename_safe(p2_url)


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def _fetch(url: str, timeout: int = 30) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "spotless-fix-dependencies/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


# ---------------------------------------------------------------------------
# Local manifest cache (avoids re-fetching artifact lists on every run)
# ---------------------------------------------------------------------------

def _manifest_cache_key(p2_url: str) -> Path:
    key = hashlib.md5(p2_url.encode()).hexdigest()
    return MANIFEST_CACHE / (key + ".json")


def _load_cached_manifest(p2_url: str) -> Optional[list[tuple[str, str]]]:
    path = _manifest_cache_key(p2_url)
    if not path.exists():
        return None
    age_hours = (time.time() - path.stat().st_mtime) / 3600
    if age_hours > MANIFEST_TTL_HOURS:
        return None
    try:
        data = json.loads(path.read_text())
        return [(a["id"], a["version"]) for a in data]
    except Exception:
        return None


def _save_manifest(p2_url: str, artifacts: list[tuple[str, str]]) -> None:
    MANIFEST_CACHE.mkdir(parents=True, exist_ok=True)
    _manifest_cache_key(p2_url).write_text(
        json.dumps([{"id": id_, "version": ver} for id_, ver in artifacts])
    )


# ---------------------------------------------------------------------------
# P2 repository helpers
# ---------------------------------------------------------------------------

def _fetch_composite_content(url: str) -> bytes:
    """Try compositeContent.jar first, then .xml.xz. Raises on both failures."""
    try:
        data = _fetch(url.rstrip("/") + "/compositeContent.jar", timeout=20)
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            return zf.read("compositeContent.xml")
    except Exception:
        return lzma.decompress(
            _fetch(url.rstrip("/") + "/compositeContent.xml.xz", timeout=20)
        )


def discover_eclipse_release_url(base_url: str, mirror: Optional[str] = None) -> str:
    """
    Query the Eclipse P2 composite to find the release child (e.g. R-4.35-202502280140).
    Tries the mirror first, falls back to canonical URL if mirror lacks the composite.
    Always returns the canonical (non-mirrored) release URL.
    """
    urls_to_try = []
    if mirror:
        urls_to_try.append(_apply_mirror(base_url, mirror))
    urls_to_try.append(base_url)

    for url in urls_to_try:
        try:
            content = _fetch_composite_content(url)
            root = ET.fromstring(content)
            release = next(
                (c.get("location", "") for c in root.findall(".//child")
                 if re.match(r"R-\d+\.\d+-\d+", c.get("location", ""))),
                None,
            )
            if release:
                return base_url.rstrip("/") + "/" + release + "/"
        except Exception:
            continue

    raise RuntimeError(f"Could not discover release URL for {base_url}")


def _apply_mirror(url: str, mirror: Optional[str]) -> str:
    """Replace https://download.eclipse.org/eclipse/ with the mirror prefix."""
    if not mirror:
        return url
    canonical = "https://download.eclipse.org/eclipse/"
    return mirror.rstrip("/") + "/" + url[len(canonical):] if url.startswith(canonical) else url


def _fetch_p2_artifacts(p2_url: str, mirror: Optional[str] = None) -> list[tuple[str, str]]:
    """
    Download and parse artifacts.xml.xz; return [(id, version)] for osgi.bundle entries.
    Always uses the canonical URL for metadata (mirrors may not have it).
    """
    for url in ([_apply_mirror(p2_url, mirror), p2_url] if mirror else [p2_url]):
        try:
            raw = _fetch(url.rstrip("/") + "/artifacts.xml.xz", timeout=60)
            root = ET.fromstring(lzma.decompress(raw))
            return [
                (a.get("id", ""), a.get("version", ""))
                for a in root.findall(".//artifact")
                if a.get("classifier") == "osgi.bundle" and a.get("id") and a.get("version")
            ]
        except Exception:
            if url == p2_url:
                raise  # both failed
    return []


def get_p2_artifacts(p2_url: str, mirror: Optional[str] = None) -> list[tuple[str, str]]:
    """Return artifact list, using local cache when available."""
    cached = _load_cached_manifest(p2_url)
    if cached is not None:
        return cached
    artifacts = _fetch_p2_artifacts(p2_url, mirror)
    _save_manifest(p2_url, artifacts)
    return artifacts


# ---------------------------------------------------------------------------
# Bundle pool population
# ---------------------------------------------------------------------------

def _download_jar(jar_url: str, dest: Path) -> tuple[str, bool, str]:
    if dest.exists() and dest.stat().st_size > 0:
        return dest.name, True, "cached"
    try:
        dest.write_bytes(_fetch(jar_url, timeout=120))
        if dest.stat().st_size == 0:
            dest.unlink(missing_ok=True)
            return dest.name, False, "empty response"
        return dest.name, True, "downloaded"
    except Exception as e:
        dest.unlink(missing_ok=True)
        return dest.name, False, str(e)


def _prefill_single_pool(p2_url: str, download_base_url: str, canonical_url: Optional[str] = None) -> None:
    """
    Populate one bundle pool directory (keyed by p2_url) by downloading from download_base_url.
    canonical_url: the URL to use for fetching the artifact list (defaults to p2_url).
    """
    dest_dir = bundle_pool_dir(p2_url)
    dest_dir.mkdir(parents=True, exist_ok=True)
    plugins_url = download_base_url.rstrip("/") + "/plugins"
    artifact_source = canonical_url or p2_url

    print(f"    Bundle pool : {dest_dir.name}")

    # Fast-path via cached manifest (keyed by p2_url so each pool has its own manifest)
    cached = _load_cached_manifest(p2_url)
    if cached:
        missing_fast = [
            (id_, ver) for id_, ver in cached
            if not (dest_dir / f"{id_}_{ver}.jar").exists()
            or (dest_dir / f"{id_}_{ver}.jar").stat().st_size == 0
        ]
        if not missing_fast:
            print(f"    ✓ All {len(cached)} artifacts already cached (manifest hit).")
            return

    print("    Fetching artifact list...", end=" ", flush=True)
    artifacts = get_p2_artifacts(artifact_source)  # fetch from canonical URL
    _save_manifest(p2_url, artifacts)  # save under the pool's own key
    print(f"{len(artifacts)} artifacts")

    missing = [
        (id_, ver) for id_, ver in artifacts
        if not (dest_dir / f"{id_}_{ver}.jar").exists()
        or (dest_dir / f"{id_}_{ver}.jar").stat().st_size == 0
    ]

    if not missing:
        print(f"    ✓ All {len(artifacts)} artifacts already cached.")
        return

    print(f"    Downloading {len(missing)}/{len(artifacts)} missing artifacts...")

    failed = []
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as pool:
        futures = {
            pool.submit(
                _download_jar,
                f"{plugins_url}/{id_}_{ver}.jar",
                dest_dir / f"{id_}_{ver}.jar",
            ): f"{id_}_{ver}.jar"
            for id_, ver in missing
        }
        for future in as_completed(futures):
            name, ok, info = future.result()
            print(f"      {'✓' if ok else '✗'} {name}" + (f"  → {info}" if not ok else ""))
            if not ok:
                failed.append(name)

    if failed:
        print(f"\n    WARNING: {len(failed)} download(s) failed.")
        sys.exit(1)
    print(f"    ✓ Downloaded {len(missing)} artifacts.")


def prefill_bundle_pool(p2_url: str, mirror: Optional[str] = None) -> None:
    """
    Download all osgi.bundle JARs from a P2 repository into the Equo bundle pool.

    Always uses the canonical (non-mirrored) URL as the bundle pool key.
    Eclipse P2 mirrors (e.g. ftp.fau.de) only mirror metadata files, not JARs;
    Equo falls back to canonical URLs for JAR downloads regardless of mirror config.
    """
    print(f"\n  P2 URL  : {p2_url}")
    _prefill_single_pool(p2_url, p2_url)


# ---------------------------------------------------------------------------
# Project configuration detection (targeted scan, no deep glob)
# ---------------------------------------------------------------------------

def _read_build_scripts(project_dir: Path) -> list[tuple[Path, str]]:
    results = []
    for subdir in BUILD_SCRIPT_DIRS:
        d = project_dir / subdir
        if not d.exists():
            continue
        glob = "**/*.kts" if subdir != "." else "*.kts"
        for p in d.glob(glob):
            if "/build/" in str(p):
                continue
            try:
                results.append((p, p.read_text(errors="ignore")))
            except OSError:
                pass
        # Also check .gradle files at the same location
        glob_g = "**/*.gradle" if subdir != "." else "*.gradle"
        for p in d.glob(glob_g):
            if "/build/" in str(p):
                continue
            try:
                results.append((p, p.read_text(errors="ignore")))
            except OSError:
                pass
    return results


def find_libs_versions_toml(project_dir: Path) -> Optional[Path]:
    """Return the first libs.versions.toml that contains a spotless-gradle entry."""
    candidates = [
        project_dir / "gradle" / "libs.versions.toml",
        project_dir / "gradle" / "build-logic-common" / "gradle" / "libs.versions.toml",
        project_dir / "build-logic" / "common" / "gradle" / "libs.versions.toml",
    ]
    for path in candidates:
        if path.exists() and "spotless-gradle" in path.read_text(errors="ignore"):
            return path
    return None


def read_spotless_version(toml_path: Path) -> Optional[str]:
    m = re.search(r'spotless-gradle\s*=\s*"([^"]+)"', toml_path.read_text())
    return m.group(1) if m else None


def find_spotless_lib_extra_jar(spotless_version: str) -> Optional[Path]:
    lib_extra_dir = GRADLE_CACHE / "com.diffplug.spotless" / "spotless-lib-extra"
    if not lib_extra_dir.exists():
        return None
    jars = [
        p for p in lib_extra_dir.rglob("spotless-lib-extra-*.jar")
        if "sources" not in p.name and p.stat().st_size > 0
    ]
    return max(jars, key=lambda p: [int(x) for x in re.findall(r"\d+", p.stem)]) if jars else None


def get_greclipse_default_eclipse_version(lib_extra_jar: Path) -> Optional[str]:
    """Extract the JVM 17+ default Eclipse version for greclipse from the JAR class."""
    try:
        with zipfile.ZipFile(lib_extra_jar) as zf:
            cls = "com/diffplug/spotless/extra/groovy/GrEclipseFormatterStep.class"
            if cls not in zf.namelist():
                return None
            data = zf.read(cls)
        versions = re.findall(r"4\.(\d+)", data.decode("latin-1"))
        return "4." + max(versions, key=int) if versions else None
    except Exception:
        return None


def greclipse_version(eclipse_ver: str) -> str:
    e = int(eclipse_ver.split(".")[1])
    if e >= 28:
        return f"5.{e - 28}.0"
    elif e >= 18:
        return f"4.{e - 18}.0"
    return f"3.{e - 8}.0"


def detect_config(project_dir: Path) -> dict:
    """Detect greclipse usage and Eclipse Java formatter version + mirrors."""
    scripts = _read_build_scripts(project_dir)
    uses_greclipse = any("greclipse" in content for _, content in scripts)

    eclipse_java_configs: list[dict] = []
    seen: set[str] = set()
    for _, content in scripts:
        for m in re.finditer(r'eclipse\(["\'](\d+\.\d+)["\']', content):
            ver = m.group(1)
            if ver in seen:
                continue
            seen.add(ver)
            cfg: dict = {"version": ver}
            # Detect withP2Mirrors — the mirror key is the canonical download.eclipse.org URL
            mirror_m = re.search(
                r'withP2Mirrors\s*\(.*?"https://download\.eclipse\.org/eclipse/"\s*to\s*"([^"]+)"',
                content,
                re.DOTALL,
            )
            if mirror_m:
                cfg["mirror"] = mirror_m.group(1)
            eclipse_java_configs.append(cfg)

    return {"greclipse": uses_greclipse, "eclipse_java": eclipse_java_configs}


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    project_dir = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()
    print(f"Project : {project_dir.name}")
    print(f"Path    : {project_dir}")

    toml = find_libs_versions_toml(project_dir)
    if not toml:
        print("ERROR: Could not find libs.versions.toml", file=sys.stderr)
        sys.exit(1)

    spotless_version = read_spotless_version(toml)
    if not spotless_version:
        print("ERROR: spotless-gradle version not found", file=sys.stderr)
        sys.exit(1)
    print(f"Spotless: {spotless_version}")

    lib_extra_jar = find_spotless_lib_extra_jar(spotless_version)
    if lib_extra_jar:
        print(f"Lib-extra: {lib_extra_jar.name}")

    config = detect_config(project_dir)
    print(f"GrEclipse usage: {'yes' if config['greclipse'] else 'no'}")
    print(f"Eclipse Java formatter: {[c['version'] for c in config['eclipse_java']]}")

    # --- GrEclipse (groovyGradle) ---
    if config["greclipse"]:
        eclipse_ver = (
            get_greclipse_default_eclipse_version(lib_extra_jar) if lib_extra_jar else None
        ) or "4.35"
        gr_ver = greclipse_version(eclipse_ver)
        greclipse_url = (
            f"https://groovy.jfrog.io/artifactory/plugins-release/org/codehaus/groovy/"
            f"groovy-eclipse-integration/{gr_ver}/e{eclipse_ver}/"
        )
        print(f"\n=== GrEclipse {gr_ver} / Eclipse {eclipse_ver} ===")
        prefill_bundle_pool(greclipse_url)

    # --- Eclipse Java formatter ---
    for cfg in config["eclipse_java"]:
        ver = cfg["version"]
        mirror = cfg.get("mirror")
        base_url = f"https://download.eclipse.org/eclipse/updates/{ver}/"
        print(f"\n=== Eclipse {ver} Java formatter ===")
        try:
            release_url = discover_eclipse_release_url(base_url, mirror)
            print(f"  Release: {release_url.rstrip('/')}")
            prefill_bundle_pool(release_url, mirror)
        except Exception as e:
            print(f"  ERROR: {e}", file=sys.stderr)
            sys.exit(1)

    print("\n✓ P2 bundle pool is up to date.")


if __name__ == "__main__":
    main()
