#!/bin/sh
set -eu

# PDF prevalence 2.17
# Word prevalence 2.94
# Excel prevalence 3.13

# plot_val_test_pr.sh --run-dir runs/multimodal_pbs --prevalence 0.05

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
PYTHON_BIN=${PYTHON_BIN:-python3}
TARGET="$PROJECT_ROOT/plot_val_test_pr.py"

if [ ! -f "$TARGET" ]; then
  echo "[ERROR] plot_val_test_pr.py not found at: $TARGET" >&2
  echo "        Expected this wrapper under: $PROJECT_ROOT/script/ or $PROJECT_ROOT/scripts/" >&2
  exit 1
fi

cd "$PROJECT_ROOT"
exec "$PYTHON_BIN" "$TARGET" "$@"
