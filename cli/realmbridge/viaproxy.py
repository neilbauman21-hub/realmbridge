"""Bootstrap and launch ViaProxy (macOS and Linux, arm64 and x64).

ViaProxy (github.com/ViaVersion/ViaProxy) is the Java program that actually
speaks Bedrock/NetherNet and translates it to the Java protocol. This module
makes sure a JDK and the ViaProxy jar are present, then launches its GUI.

If a ViaProxy.jar (and optional plugins/ directory) sits next to this package
— as in the distributable tarball — it is installed into the cache instead of
downloading from GitHub, so patched builds travel with the dist.
"""

from __future__ import annotations

import os
import platform as _platform
import re
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

import requests

CACHE_DIR = Path.home() / ".bedrock-realm-bridge"
JDK_DIR = CACHE_DIR / "jdk"
VIAPROXY_JAR = CACHE_DIR / "ViaProxy.jar"

# Directory this package was run from (dist root when unpacked from a tarball).
DIST_DIR = Path(__file__).resolve().parent.parent


def _adoptium_url() -> str:
    os_name = {"darwin": "mac", "linux": "linux"}.get(sys.platform)
    if os_name is None:
        raise RuntimeError(f"Unsupported OS: {sys.platform}. Install Java 17+ manually.")
    machine = _platform.machine().lower()
    arch = {"arm64": "aarch64", "aarch64": "aarch64", "x86_64": "x64", "amd64": "x64"}.get(machine, "x64")
    return f"https://api.adoptium.net/v3/binary/latest/21/ga/{os_name}/{arch}/jdk/hotspot/normal/eclipse"


VIAPROXY_LATEST = "https://api.github.com/repos/ViaVersion/ViaProxy/releases/latest"

GITHUB_HEADERS = {"User-Agent": "bedrock-realm-bridge", "Accept": "application/vnd.github+json"}


def _system_java_major() -> int | None:
    """Major version of a `java` already on PATH, or None if unusable."""
    try:
        out = subprocess.run(
            ["java", "-version"], capture_output=True, text=True, timeout=15
        ).stderr
    except (FileNotFoundError, subprocess.SubprocessError):
        return None
    m = re.search(r'version "(\d+)(?:\.(\d+))?', out)
    if not m:
        return None
    major = int(m.group(1))
    # Old scheme "1.8" -> major 8.
    if major == 1 and m.group(2):
        return int(m.group(2))
    return major


def _bundled_java() -> Path | None:
    # macOS JDK archives nest under Contents/Home; Linux ones don't.
    hits = list(JDK_DIR.glob("*/Contents/Home/bin/java")) or list(JDK_DIR.glob("*/bin/java"))
    return hits[0] if hits else None


def _download(url: str, dest: Path, headers: dict | None = None) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    with requests.get(url, stream=True, timeout=120, headers=headers) as r:
        r.raise_for_status()
        with open(dest, "wb") as f:
            for chunk in r.iter_content(chunk_size=1 << 16):
                f.write(chunk)


def ensure_java() -> str:
    """Return a path/command for a Java >= 17 launcher, downloading if needed."""
    major = _system_java_major()
    if major and major >= 17:
        return "java"

    bundled = _bundled_java()
    if bundled:
        return str(bundled)

    print("No suitable Java found. Downloading Temurin JDK 21...")
    JDK_DIR.mkdir(parents=True, exist_ok=True)
    archive = JDK_DIR / "jdk.tar.gz"
    _download(_adoptium_url(), archive)
    with tarfile.open(archive) as tf:
        tf.extractall(JDK_DIR)
    archive.unlink(missing_ok=True)

    bundled = _bundled_java()
    if not bundled:
        raise RuntimeError("JDK download succeeded but no java binary was found.")
    bundled.chmod(0o755)
    print(f"JDK ready: {bundled}")
    return str(bundled)


def ensure_viaproxy() -> Path:
    """Install the bundled ViaProxy jar, or download the latest release."""
    # ViaBedrock's experimental features carry the inventory-transaction and
    # item-use handling that Realms need; preseed the flag before first launch
    # (ViaProxy fills in all other keys with defaults).
    vb_config = CACHE_DIR / "viabedrock.yml"
    if not vb_config.exists():
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        vb_config.write_text("enable-experimental-features: true\n")

    # Prefer a jar shipped alongside this package (patched dist build).
    bundled = DIST_DIR / "ViaProxy.jar"
    if bundled.exists() and not VIAPROXY_JAR.exists():
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        shutil.copy2(bundled, VIAPROXY_JAR)
        bundled_plugins = DIST_DIR / "plugins"
        if bundled_plugins.is_dir():
            (CACHE_DIR / "plugins").mkdir(exist_ok=True)
            for jar in bundled_plugins.glob("*.jar"):
                shutil.copy2(jar, CACHE_DIR / "plugins" / jar.name)
        print(f"Installed bundled ViaProxy (+plugins) from {DIST_DIR}")

    if VIAPROXY_JAR.exists() and VIAPROXY_JAR.stat().st_size > 0:
        return VIAPROXY_JAR

    print("Downloading latest ViaProxy release...")
    rel = requests.get(VIAPROXY_LATEST, headers=GITHUB_HEADERS, timeout=30)
    rel.raise_for_status()
    assets = rel.json().get("assets", [])
    # The "+java8" asset is a legacy build whose log4j breaks on modern JVMs;
    # we always run Java 17+, so take the regular jar.
    jar = next(
        (
            a
            for a in assets
            if a["name"].endswith(".jar")
            and "sources" not in a["name"]
            and "java8" not in a["name"]
        ),
        None,
    )
    if not jar:
        raise RuntimeError("Could not find a ViaProxy .jar in the latest release.")
    _download(jar["browser_download_url"], VIAPROXY_JAR, headers=GITHUB_HEADERS)
    print(f"ViaProxy ready: {VIAPROXY_JAR} ({jar['name']})")
    return VIAPROXY_JAR


def launch(java_bin: str, jar: Path) -> int:
    """Launch the ViaProxy GUI and stream its output. Blocks until it exits."""
    env = dict(os.environ)
    # If we're using the bundled JDK, point JAVA_HOME at it too.
    if java_bin != "java" and java_bin.endswith("/bin/java"):
        env["JAVA_HOME"] = str(Path(java_bin).parents[1])
    print(f"\nLaunching ViaProxy: {java_bin} -jar {jar}\n")
    proc = subprocess.Popen([java_bin, "-jar", str(jar)], cwd=str(CACHE_DIR), env=env)
    return proc.wait()
