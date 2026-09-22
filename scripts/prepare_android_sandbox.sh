#!/usr/bin/env bash
#
# Prepare Android sandbox assets:
#   1. Download Alpine Linux aarch64 minirootfs
#   2. Download PRoot aarch64 static binary from Termux packages
#   3. Place both into src/android/app/src/main/assets/
#   4. Stage jniLibs/arm64-v8a/libproot.so from the proot asset when missing
#      (RootfsManager requires nativeLibraryDir/libproot.so at runtime; the
#      installer only extracts lib/**/*.so from the APK into that directory,
#      and Android 10+ W^X makes it the only executable location)
#
# Usage: ./scripts/prepare_android_sandbox.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/src/android/app/src/main/assets"

ALPINE_VERSION="3.21"
ALPINE_RELEASE="3.21.3"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/releases/aarch64/alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz"

# Termux proot package — aarch64 static binary
PROOT_VERSION="5.1.107.92"
PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${PROOT_VERSION}_aarch64.deb"

mkdir -p "$ASSETS_DIR"

ROOTFS_FILE="$ASSETS_DIR/alpine-minirootfs.tar.gz"
PROOT_FILE="$ASSETS_DIR/proot-aarch64"
JNILIBS_DIR="$PROJECT_ROOT/src/android/app/src/main/jniLibs/arm64-v8a"
JNILIBS_PROOT="$JNILIBS_DIR/libproot.so"

# --- Alpine rootfs ---
if [ -f "$ROOTFS_FILE" ]; then
    echo "✓ Alpine rootfs already exists: $ROOTFS_FILE"
else
    echo "Downloading Alpine Linux ${ALPINE_RELEASE} aarch64 minirootfs..."
    curl -fSL -o "$ROOTFS_FILE" "$ALPINE_URL"
    echo "✓ Downloaded: $ROOTFS_FILE ($(du -h "$ROOTFS_FILE" | cut -f1))"
fi

# --- PRoot binary ---
if [ -f "$PROOT_FILE" ]; then
    echo "✓ PRoot binary already exists: $PROOT_FILE"
else
    echo "Downloading PRoot ${PROOT_VERSION} aarch64 from Termux..."

    TMPDIR="$(mktemp -d)"
    trap 'rm -rf "$TMPDIR"' EXIT

    DEB_FILE="$TMPDIR/proot.deb"
    curl -fSL -o "$DEB_FILE" "$PROOT_DEB_URL"

    # Extract .deb (it's an ar archive containing data.tar.xz)
    cd "$TMPDIR"
    ar x "$DEB_FILE"

    # Extract data archive
    if [ -f "data.tar.xz" ]; then
        tar xf data.tar.xz
    elif [ -f "data.tar.gz" ]; then
        tar xzf data.tar.gz
    elif [ -f "data.tar.zst" ]; then
        zstd -d data.tar.zst -o data.tar
        tar xf data.tar
    else
        echo "Error: Could not find data archive in .deb"
        ls -la "$TMPDIR"
        exit 1
    fi

    # Find the proot binary
    PROOT_BIN=$(find "$TMPDIR" -name "proot" -type f | head -1)
    if [ -z "$PROOT_BIN" ]; then
        echo "Error: Could not find proot binary in extracted .deb"
        find "$TMPDIR" -type f
        exit 1
    fi

    cp "$PROOT_BIN" "$PROOT_FILE"
    chmod +x "$PROOT_FILE"
    cd "$PROJECT_ROOT"

    echo "✓ Extracted PRoot binary: $PROOT_FILE ($(du -h "$PROOT_FILE" | cut -f1))"
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
