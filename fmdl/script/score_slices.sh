#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

PYTHON_BIN=${PYTHON_BIN:-python3}
SCORER="$PROJECT_ROOT/score_slices.py"

# overriding
# MODEL=llama3 BACKEND=ollama ./scripts/score_slices.sh
# BACKEND=openai MODEL=gpt-4o-mini OPENAI_API_KEY="sk-..." ./scripts/score_slices.sh

# defaults
BACKEND=${BACKEND:-ollama}
MODEL=${MODEL:-llama3}
FEW_SHOT_DIR=${FEW_SHOT_DIR:-"$PROJECT_ROOT/data/few-shot"}
OUTPUT_DIR=${OUTPUT_DIR:-"$PROJECT_ROOT/outputs"}

PDF_SLICES=${PDF_SLICES:-"$PROJECT_ROOT/data/slices/pdf/mware"}
WORD_SLICES=${WORD_SLICES:-"$PROJECT_ROOT/data/slices/doc/mware"}
EXCEL_SLICES=${EXCEL_SLICES:-"$PROJECT_ROOT/data/slices/xls/mware"}

if [ ! -f "$SCORER" ]; then
  echo "[ERROR] score_slices.py not found at: $SCORER" >&2
  exit 1
fi

if [ ! -d "$FEW_SHOT_DIR" ]; then
  echo "[ERROR] few-shot directory not found at: $FEW_SHOT_DIR" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
cd "$PROJECT_ROOT"

run_score() {
  file_type=$1
  slices_dir=$2
  output_prefix=$3

  if [ ! -d "$slices_dir" ]; then
    echo "[WARN] skipping $file_type; slices directory not found: $slices_dir" >&2
    return 0
  fi

  echo "[*] scoring $file_type slices: $slices_dir"
  "$PYTHON_BIN" "$SCORER" "$slices_dir" \
    --backend "$BACKEND" \
    --model "$MODEL" \
    --file-type "$file_type" \
    --few-shot-dir "$FEW_SHOT_DIR" \
    --output-prefix "$OUTPUT_DIR/$output_prefix" \
    --include-meta \
    --verbose \
    "$@"
}

run_score pdf "$PDF_SLICES" pdf_results "$@"
run_score word "$WORD_SLICES" word_results "$@"
run_score excel "$EXCEL_SLICES" excel_results "$@"

echo "[✓] scoring complete. Outputs written under: $OUTPUT_DIR"
