#!/bin/sh
set -eu

# plot_pbs_tracking.sh --csv runs/multimodal_pbs/pbs_epoch_metrics.csv --out-dir runs/multimodal_pbs/plots --title-prefix FMDL --dpi 220

SCRIPT_PATH="$0"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$SCRIPT_PATH")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

PYTHON_BIN="${PYTHON_BIN:-python3}"
PLOTTER="$PROJECT_ROOT/plot_pbs_tracking.py"

if [ ! -f "$PLOTTER" ]; then
  echo "[ERROR] plot_pbs_tracking.py not found at: $PLOTTER" >&2
  exit 1
fi

# Use a non-interactive Matplotlib backend by default for servers/headless runs.
# If caller passes --show, leave MPLBACKEND alone so an interactive backend can be used.
USE_SHOW=0
for arg in "$@"; do
  if [ "$arg" = "--show" ]; then
    USE_SHOW=1
    break
  fi
done

if [ "$USE_SHOW" -eq 0 ]; then
  export MPLBACKEND="${MPLBACKEND:-Agg}"
fi

cd "$PROJECT_ROOT"
exec "$PYTHON_BIN" "$PLOTTER" "$@"
