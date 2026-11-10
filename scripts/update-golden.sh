#!/usr/bin/env bash
# Rewrite the conformance suite's golden data from a complydoc checkout: the Python
# package's own answers, which the Java version is checked against.
#
#   scripts/update-golden.sh ../complydoc
#
# Copy the shipped configuration over too, so the two read the same rules:
#   cp complydoc4j-conformance/src/test/resources/golden/config/*.yaml \
#      complydoc4j-core/src/main/resources/io/github/complydoc4j/config/
set -euo pipefail
complydoc="${1:?usage: update-golden.sh <complydoc checkout>}"
golden="$(cd "$(dirname "$0")/.." && pwd)/complydoc4j-conformance/src/test/resources/golden"
(cd "$complydoc" && uv run --group integrations python src/scripts/java_conformance.py "$golden")
