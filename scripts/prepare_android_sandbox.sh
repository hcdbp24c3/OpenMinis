#!/usr/bin/env bash
#
# Prepare Android sandbox assets in src/android/app/src/main/assets/:
#   1. Download the Alpine Linux aarch64 minirootfs and bake ROOTFS_PACKAGES
#      (git, ripgrep) into it — see scripts/android_rootfs_add_packages.py
#   2. Check that the PRoot binary is present (built by deps/build_proot.sh)
#   3. Stage jniLibs/arm64-v8a/libproot.so from the proot asset when missing
#      (RootfsManager requires nativeLibraryDir/libproot.so at runtime; the
#      installer only extracts lib/**/*.so from the APK into that directory,
#      and Android 10+ W^X makes it the only executable location)
#
# The PRoot binary (assets/proot-aarch64) is produced by deps/build_proot.sh,
# which must run first. This script used to fall back to downloading a Termux
# proot .deb, but that package version has been rotated out of
# packages.termux.dev (404), so the fallback could only fail.
#
# Usage: ./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/src/android/app/src/main/assets"

ALPINE_VERSION="3.21"
ALPINE_RELEASE="3.21.3"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/releases/aarch64/alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz"

# [T-coding-m1-project] Packages installed into the rootfs at build time, so the
# agent has them on its first command instead of `apk add`-ing them over the
# network. Dependencies are resolved automatically. Each package here is in
# every fresh install, so keep the list short: git + ripgrep add ~7 MB to the
# APK and ~16 MB on the device.
ROOTFS_PACKAGES="git ripgrep"
ADD_PACKAGES="$SCRIPT_DIR/android_rootfs_add_packages.py"
ROOTFS_CACHE_DIR="$PROJECT_ROOT/deps/.cache/android-rootfs"
ROOTFS_BASE="$ROOTFS_CACHE_DIR/alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz"
ROOTFS_STAMP="$ROOTFS_CACHE_DIR/rootfs.stamp"

mkdir -p "$ASSETS_DIR"

ROOTFS_FILE="$ASSETS_DIR/alpine-minirootfs.tar.gz"
PROOT_FILE="$ASSETS_DIR/proot-aarch64"
JNILIBS_DIR="$PROJECT_ROOT/src/android/app/src/main/jniLibs/arm64-v8a"
JNILIBS_PROOT="$JNILIBS_DIR/libproot.so"

# --- Alpine rootfs ---
# The stamp records what the asset was built from. A rootfs with no stamp is a
# stock minirootfs left by an older checkout of this script (no git/rg), and a
# stale stamp means the release, the package list or the tool changed — both
# are rebuilt. The stamp lives in the cache dir, not in assets/, so it is not
# packaged into the APK.
ROOTFS_WANT="alpine=${ALPINE_RELEASE} packages=${ROOTFS_PACKAGES} tool=$(cksum < "$ADD_PACKAGES")"
if [ -f "$ROOTFS_FILE" ] && [ -f "$ROOTFS_STAMP" ] && [ "$(cat "$ROOTFS_STAMP")" = "$ROOTFS_WANT" ]; then
    echo "✓ Alpine rootfs already exists (with: $ROOTFS_PACKAGES): $ROOTFS_FILE"
else
    command -v python3 >/dev/null 2>&1 || {
        echo "Error: python3 is required to add packages ($ROOTFS_PACKAGES) to the rootfs." >&2
        exit 1
    }
    mkdir -p "$ROOTFS_CACHE_DIR"
    if [ ! -f "$ROOTFS_BASE" ]; then
        echo "Downloading Alpine Linux ${ALPINE_RELEASE} aarch64 minirootfs..."
        curl -fSL -o "$ROOTFS_BASE.part" "$ALPINE_URL"
        mv "$ROOTFS_BASE.part" "$ROOTFS_BASE"
    fi
    echo "Adding packages to the rootfs: $ROOTFS_PACKAGES"
    rm -f "$ROOTFS_STAMP"
    # shellcheck disable=SC2086  # ROOTFS_PACKAGES is a word list on purpose
    python3 "$ADD_PACKAGES" \
        --base "$ROOTFS_BASE" \
        --out "$ROOTFS_FILE" \
        --cache "$ROOTFS_CACHE_DIR/apk" \
        --alpine-version "$ALPINE_VERSION" \
        $ROOTFS_PACKAGES
    printf '%s\n' "$ROOTFS_WANT" > "$ROOTFS_STAMP"
    echo "✓ Built: $ROOTFS_FILE ($(du -h "$ROOTFS_FILE" | cut -f1))"
fi

# --- PRoot binary ---
if [ -f "$PROOT_FILE" ]; then
    echo "✓ PRoot binary present: $PROOT_FILE"
else
    echo "Error: $PROOT_FILE is missing." >&2
    echo "       It is built from source by ./deps/build_proot.sh — run that first," >&2
    echo "       then rerun this script." >&2
    exit 1
fi

# --- Stage libproot.so into jniLibs (fallback) ---
# deps/build_proot.sh normally installs BOTH assets/proot-aarch64 and
# jniLibs/arm64-v8a/libproot.so (the fork build). When that script has not
# run — CI checkouts, quick local builds — the APK would package the loaders
# but not proot itself, and RootfsManager.installProotIfNeeded() fails at
# runtime with "PRoot binary not available at .../lib/arm64/libproot.so".
# Copy the prepared asset into jniLibs so every Gradle build yields a
# terminal-capable APK. Never overwrite a fork-built libproot.so.
mkdir -p "$JNILIBS_DIR"
if [ -f "$JNILIBS_PROOT" ]; then
    echo "✓ libproot.so already present: $JNILIBS_PROOT"
else
    cp "$PROOT_FILE" "$JNILIBS_PROOT"
    chmod +x "$JNILIBS_PROOT"
    echo "✓ Staged jniLibs libproot.so from proot asset: $JNILIBS_PROOT ($(du -h "$JNILIBS_PROOT" | cut -f1))"
fi

# Fail loudly if we still cannot provide proot as a native library — a
# green build that omits it produces an APK whose terminal always throws.
if [ ! -s "$JNILIBS_PROOT" ]; then
    echo "Error: $JNILIBS_PROOT is missing or empty." >&2
    echo "Run ./deps/build_proot.sh (preferred) or re-run this script." >&2
    exit 1
fi

# Loaders ship with the repo; without them proot launches but every guest
# execve fails W^X (see deps/build_proot.sh header).
for loader in libproot-loader.so libproot-loader32.so; do
    if [ ! -f "$JNILIBS_DIR/$loader" ]; then
        echo "Warning: $JNILIBS_DIR/$loader is missing." >&2
        echo "Restore: git checkout -- src/android/app/src/main/jniLibs/arm64-v8a/$loader" >&2
        echo "Or rerun ./deps/build_proot.sh to verify the full sandbox set." >&2
    fi
done

echo ""
echo "Assets ready in: $ASSETS_DIR"
ls -lh "$ASSETS_DIR"
echo ""
echo "jniLibs ready in: $JNILIBS_DIR"
ls -lh "$JNILIBS_DIR"
