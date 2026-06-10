#!/usr/bin/env bash
# Install memgraph's two native-binary dependencies: babashka (bb) and the
# Datalevin pod binary (dtlv). Both are GraalVM native images — no JVM.
set -euo pipefail

BB_VERSION="${BB_VERSION:-1.12.218}"
DTLV_VERSION="${DTLV_VERSION:-0.10.18}"
INSTALL_DIR="${INSTALL_DIR:-/usr/local/bin}"

if ! command -v bb >/dev/null 2>&1; then
  echo "Installing babashka ${BB_VERSION}..."
  curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
  chmod +x install
  ./install --dir "$INSTALL_DIR" --version "$BB_VERSION"
  rm -f install
fi
bb --version

if ! command -v dtlv >/dev/null 2>&1; then
  echo "Installing dtlv ${DTLV_VERSION}..."
  case "$(uname -sm)" in
    "Linux x86_64")  ASSET="dtlv-${DTLV_VERSION}-ubuntu-22.04-amd64.zip" ;;
    "Darwin arm64")  ASSET="dtlv-${DTLV_VERSION}-macos-14-aarch64.zip" ;;
    "Linux aarch64") ASSET="dtlv-${DTLV_VERSION}-ubuntu-24.04-arm-aarch64.zip" ;;
    *) echo "Unsupported platform: $(uname -sm)" >&2; exit 1 ;;
  esac
  TMP="$(mktemp -d)"
  curl -sL -o "$TMP/dtlv.zip" "https://github.com/datalevin/datalevin/releases/download/${DTLV_VERSION}/${ASSET}"
  (cd "$TMP" && unzip -oq dtlv.zip && chmod +x dtlv && mv dtlv "$INSTALL_DIR/dtlv")
  rm -rf "$TMP"
fi
dtlv help >/dev/null && echo "dtlv ${DTLV_VERSION} OK"

echo "Done. Try: bin/memgraph init"
