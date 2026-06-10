#!/bin/sh
set -euo pipefail

# val_test.sh --run-dir runs/multimodal_pbs --splits val test --batch-size 16 --num-workers 4 --device cuda --amp

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PYTHON_BIN="${PYTHON_BIN:-python3}"
VAL_TEST="${PROJECT_ROOT}/val_test.py"

if [[ ! -f "${VAL_TEST}" ]]; then
  echo "[ERROR] val_test.py not found at: ${VAL_TEST}" >&2
  exit 1
fi

# Run from project root so val_test.py defaults like runs/multimodal_pbs resolve correctly.
cd "${PROJECT_ROOT}"

HAS_PROJECT_ROOT=0
for arg in "$@"; do
  case "${arg}" in
    --project-root|--project-root=*)
      HAS_PROJECT_ROOT=1
      break
      ;;
  esac
done

if [[ "${HAS_PROJECT_ROOT}" -eq 1 ]]; then
  exec "${PYTHON_BIN}" "${VAL_TEST}" "$@"
else
  exec "${PYTHON_BIN}" "${VAL_TEST}" --project-root "${PROJECT_ROOT}" "$@"
fi
