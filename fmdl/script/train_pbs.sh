#!/bin/sh
set -eu

# train_pbs.sh --data-root dataset --out-dir runs/multimodal_pbs --epochs 20 --batch-size 16 --num-workers 4 --device cuda --amp

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)

PYTHON_BIN=${PYTHON_BIN:-python3}
TRAINER="${PROJECT_ROOT}/train_pbs.py"

if [ ! -f "${TRAINER}" ]; then
  echo "[ERROR] train_pbs.py not found at: ${TRAINER}" >&2
  echo "Place this script inside project_root/script/ or project_root/scripts/." >&2
  exit 1
fi

cd "${PROJECT_ROOT}"

# Keep root imports stable for dataset.py, sampler.py, and models/.
if [ -n "${PYTHONPATH:-}" ]; then
  export PYTHONPATH="${PROJECT_ROOT}:${PYTHONPATH}"
else
  export PYTHONPATH="${PROJECT_ROOT}"
fi

exec "${PYTHON_BIN}" "${TRAINER}" "$@"
