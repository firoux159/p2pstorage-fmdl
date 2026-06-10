#!/bin/sh
set -eu

# slice_files.sh --file-type pdf --input data/raw/pdf/mware --out data/slices/pdf/mware --recursive --write-hex
# slice_files.sh --file-type word --input data/raw/word/mware --out data/slices/word/mware --recursive --write-hex
# slice_files.sh --file-type excel --input data/raw/excel/mware --out data/slices/excel/mware --recursive --write-hex

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)

PYTHON_BIN=${PYTHON_BIN:-python3}
SLICE_ENTRYPOINT="${PROJECT_ROOT}/slice_files.py"

if [ ! -f "${SLICE_ENTRYPOINT}" ]; then
  echo "[ERROR] slice_files.py not found at: ${SLICE_ENTRYPOINT}" >&2
  exit 1
fi

cd "${PROJECT_ROOT}"

exec "${PYTHON_BIN}" "${SLICE_ENTRYPOINT}" "$@"
