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
# Per Chaquopy's Android wheel guidance, cibuildwheel downloads a correctly
# provisioned Android CPython sysroot (including libpython3.so for PyO3).
# A plain Linux-host maturin invocation fails during cross-link: -lpython3.
"$PYTHON" -m pip install --disable-pip-version-check "cibuildwheel==4.3.5"

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
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export CIBW_TEST_SKIP="*"
export CIBW_BUILD_VERBOSITY=1
export CIBW_ANDROID_API_LEVEL=26
mkdir -p "$DEST"
rm -f "$DEST"/gamdl-4.1.1-*.whl
(
    cd "$TEMP"
    "$PYTHON" -m cibuildwheel --only cp313-android_arm64_v8a \
        --output-dir "$DEST"
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

# pywidevine >=1.9 requires pycryptodome >=3.23; Chaquopy's CPython
# 3.13 index currently tops out at 3.21. Cross-build the newer upstream
# C-extension rather than weakening the dependency constraint.
CRYPTO_VERSION="3.23.0"
CRYPTO_SOURCE="$TEMP/pycryptodome"
mkdir -p "$CRYPTO_SOURCE" "$TEMP/sdist"
"$PYTHON" -m pip download --no-deps --no-binary=:all: \
    "pycryptodome==$CRYPTO_VERSION" --dest "$TEMP/sdist"
mapfile -t ARCHIVES < <(find "$TEMP/sdist" -maxdepth 1 -name 'pycryptodome-*.tar.gz' -type f)
if [[ "${#ARCHIVES[@]}" -ne 1 ]]; then
    echo "Expected one pinned pycryptodome source archive." >&2
    exit 5
fi
tar -xzf "${ARCHIVES[0]}" -C "$CRYPTO_SOURCE" --strip-components=1
(
    cd "$CRYPTO_SOURCE"
    "$PYTHON" -m cibuildwheel --only cp313-android_arm64_v8a \
        --output-dir "$DEST"
)
mapfile -t CRYPTO_WHEELS < <(find "$DEST" -maxdepth 1 -name "pycryptodome-$CRYPTO_VERSION-*.whl" -type f)
if [[ "${#CRYPTO_WHEELS[@]}" -ne 1 ]]; then
    echo "Expected one Android pycryptodome 3.23.0 wheel." >&2
    exit 6
fi
echo "Verified Android crypto wheel: $(basename "${CRYPTO_WHEELS[0]}")"



# pymp4 1.4.0 declares construct==2.8.8 (2016); Chaquopy's CPython
# 3.13 index supports construct==2.8.10, a same-series patch version.
# Do NOT silently relax requirements in pip: generate an explicit
# local derivative wheel with fixed RECORD and keep original program files.
"$PYTHON" -m pip install --disable-pip-version-check "wheel==0.46.1"
mkdir -p "$TEMP/pymp4-original" "$TEMP/pymp4-unpacked"
"$PYTHON" -m pip download --no-deps --only-binary=:all: \
    "pymp4==1.4.0" --dest "$TEMP/pymp4-original"
mapfile -t PYMP4_ORIGINAL < <(find "$TEMP/pymp4-original" -name 'pymp4-1.4.0-*.whl' -type f)
if [[ "${#PYMP4_ORIGINAL[@]}" -ne 1 ]]; then
    echo "Expected one pinned pymp4 1.4.0 wheel." >&2
    exit 7
fi
"$PYTHON" -m wheel unpack "${PYMP4_ORIGINAL[0]}" --dest "$TEMP/pymp4-unpacked"
PYMP4_DIR="$TEMP/pymp4-unpacked/pymp4-1.4.0"
export PYMP4_DIR
"$PYTHON" - <<'PY'
import os, pathlib, re
root = pathlib.Path(os.environ["PYMP4_DIR"])
paths = list(root.glob("*.dist-info/METADATA"))
assert len(paths) == 1, "pymp4 metadata missing"
path = paths[0]
source = path.read_text(encoding="utf-8")
updated, count = re.subn(
    r"(Requires-Dist:\s*construct\s*(?:\(\s*)?==\s*)2\.8\.8(\s*\)?)",
    r"\g<1>2.8.10\2",
    source,
)
assert count == 1, "pymp4 upstream pin changed: inspect before patching"
path.write_text(updated, encoding="utf-8")
PY
"$PYTHON" -m wheel pack "$PYMP4_DIR" --dest "$DEST"
mapfile -t PYMP4_WHEELS < <(find "$DEST" -maxdepth 1 -name "pymp4-1.4.0-*.whl" -type f)
if [[ "${#PYMP4_WHEELS[@]}" -ne 1 ]]; then
    echo "Expected one patched pymp4 1.4.0 wheel." >&2
    exit 8
fi
echo "Validated local pymp4 Android compatibility wheel: $(basename "${PYMP4_WHEELS[0]}")"
