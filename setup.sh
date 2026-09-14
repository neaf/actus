#!/usr/bin/env bash
# One-time environment check for building Actus.
# Verifies Java 21+ and Maven are available, then does a cold dependency
# resolve so the first real build (./build.sh) is fast.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "== Checking Java =="
if ! command -v java >/dev/null 2>&1; then
	echo "Java not found on PATH. Install a JDK 21+ (e.g. https://adoptium.net) and re-run." >&2
	exit 1
fi

JAVA_VERSION="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [ "${JAVA_VERSION}" -lt 21 ]; then
	echo "Java ${JAVA_VERSION} found, but 21+ is required." >&2
	exit 1
fi
echo "Java ${JAVA_VERSION} OK"

echo "== Checking Maven =="
if ! command -v mvn >/dev/null 2>&1; then
	echo "Maven not found on PATH. Install it (e.g. 'brew install maven') and re-run." >&2
	exit 1
fi
mvn -v | head -1

echo "== Checking Bitwig Extensions directory =="
BITWIG_EXT_DIR="${BITWIG_EXTENSION_DIR:-$HOME/Documents/Bitwig Studio/Extensions}"
mkdir -p "${BITWIG_EXT_DIR}"
echo "Extensions will be installed to: ${BITWIG_EXT_DIR}"

echo "== Resolving dependencies =="
mvn -q dependency:resolve

echo
echo "Setup complete. Run ./build.sh to build and install the extension."
