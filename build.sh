#!/usr/bin/env bash
# Builds the extension and installs it straight into the Bitwig Studio
# Extensions folder as Actus.bwextension (see pom.xml's
# bitwig.extension.directory property to change the target).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

MVN_ARGS=(clean package)
if [ -n "${BITWIG_EXTENSION_DIR:-}" ]; then
	MVN_ARGS+=("-Dbitwig.extension.directory=${BITWIG_EXTENSION_DIR}")
fi

mvn "${MVN_ARGS[@]}"

echo
echo "Built and installed Actus.bwextension."
echo "Restart Bitwig Studio (or rescan controllers) to pick up the change."
