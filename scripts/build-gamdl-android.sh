#!/usr/bin/env bash
# Build Gamdl 4.1.1 for Android ARM64 from the exact upstream source commit.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/gamdl-wheels"
SOURCE_COMMIT="3da517e32a1a576a4c774f8c2a0ac7db4c42fc7b"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" ]]; then
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -n "$SDK" && -d "$SDK/ndk" ]]; then
        NDK="$(find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
    fi
fi
if [[ ! -f "$NDK/source.properties" ]]; then
    echo "Android NDK missing: set ANDROID_NDK_HOME or install via sdkmanager." >&2
    exit 2
fi

HOST="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$HOST" in
    linux) TOOLCHAIN_HOST=linux-x86_64 ;;
    darwin) TOOLCHAIN_HOST=darwin-x86_64 ;;
    *) echo "Unsupported NDK host." >&2; exit 2 ;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/$TOOLCHAIN_HOST/bin"
CLANG="$BIN/aarch64-linux-android26-clang"
if [[ ! -x "$CLANG" ]]; then
    echo "Missing Android ARM64 clang: $CLANG" >&2
    exit 2
fi

PYTHON="${GAMDL_BUILD_PYTHON:-python3.13}"
"$PYTHON" -c 'import sys; assert sys.version_info[:2] == (3, 13)'
"$PYTHON" -m pip install --disable-pip-version-check "maturin==1.15.0"

TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
git -C "$TEMP" init -q
git -C "$TEMP" remote add origin https://github.com/glomatico/gamdl.git
git -C "$TEMP" fetch -q --depth 1 origin "$SOURCE_COMMIT"
git -C "$TEMP" checkout -q --detach FETCH_HEAD
ACTUAL="$(git -C "$TEMP" rev-parse HEAD)"
if [[ "$ACTUAL" != "$SOURCE_COMMIT" ]]; then
    echo "Gamdl source SHA mismatch: $ACTUAL" >&2
    exit 3
fi

rustup target add aarch64-linux-android
export ANDROID_NDK_HOME="$NDK"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"
export CC_aarch64_linux_android="$CLANG"
export AR_aarch64_linux_android="$BIN/llvm-ar"
export PYO3_CROSS_PYTHON_VERSION="3.13"
mkdir -p "$DEST"
rm -f "$DEST"/gamdl-4.1.1-*.whl
(
    cd "$TEMP"
    "$PYTHON" -m maturin build --release \
        --target aarch64-linux-android \
        --interpreter "$PYTHON" \
        --out "$DEST"
)
mapfile -t WHEELS < <(find "$DEST" -maxdepth 1 -type f -name 'gamdl-4.1.1-*.whl')
if [[ "${#WHEELS[@]}" -ne 1 ]]; then
    echo "Expected one Gamdl Android wheel, got ${#WHEELS[@]}" >&2
    exit 4
fi
case "$(basename "${WHEELS[0]}")" in
    *android*arm64_v8a.whl) ;;
    *) echo "Wheel platform is not Android ARM64: ${WHEELS[0]}" >&2; exit 4 ;;
esac
echo "Verified Android wheel: $(basename "${WHEELS[0]}")"
