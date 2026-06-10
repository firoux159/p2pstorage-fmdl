"""
Slice files into chunks
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Sequence

PROJECT_ROOT = Path(__file__).resolve().parent
UTILS_DIR = PROJECT_ROOT / "utils"

# make project root and utils importable. The utilities read root/config.py.
for p in (PROJECT_ROOT, UTILS_DIR):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

try:
    from config import (
        DEFAULT_RULES_PATH,
        WINDOW_SIZE,
        WINDOW_STEP,
        WINDOW_THR,
        WHOLE_ENTROPY_THR,
        MAX_SLICE_SIZE,
        SLICE_STRIDE,
        ENTROPY_THRESHOLD,
        KEYWORD_DENSITY_THRESHOLD,
        KEYWORDS,
    )
except Exception:  
    DEFAULT_RULES_PATH = "rules/updated_rules.yar"
    WINDOW_SIZE = 4096
    WINDOW_STEP = 1024
    WINDOW_THR = 7.2
    WHOLE_ENTROPY_THR = 7.2
    MAX_SLICE_SIZE = 2048
    SLICE_STRIDE = 512
    ENTROPY_THRESHOLD = 7.2
    KEYWORD_DENSITY_THRESHOLD = 0.05
    KEYWORDS = ("autoopen", "document_open", "workbook_open", "shell", "powershell")


def _root_relative(path_value: str | Path) -> Path:
    path = Path(path_value)
    return path if path.is_absolute() else PROJECT_ROOT / path


DEFAULT_RULES = _root_relative(DEFAULT_RULES_PATH)


PDF_TYPES = {"pdf"}
WORD_TYPES = {"word", "doc"}
EXCEL_TYPES = {"excel", "xls"}
OFFICE_TYPES = {"office", "doc_xls", "word_excel"}
VALID_TYPES = sorted(PDF_TYPES | WORD_TYPES | EXCEL_TYPES | OFFICE_TYPES)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run the correct slicer utility from project root based on file type."
    )
    parser.add_argument(
        "--file-type",
        "--type",
        required=True,
        choices=VALID_TYPES,
        help="Input family to process: pdf, word/doc, excel/xls, or office.",
    )
    parser.add_argument("--input", "-i", required=True, type=Path, help="Input file or directory.")
    parser.add_argument("--out", "-o", required=True, type=Path, help="Output directory.")
    parser.add_argument(
        "--rules",
        "-r",
        type=Path,
        default=DEFAULT_RULES,
        help=f"YARA source file, compiled bundle, or rules directory. Default: {DEFAULT_RULES}",
    )
    parser.add_argument(
        "--recursive",
        action="store_true",
        help="Recurse into input subdirectories when input is a directory.",
    )

    # pdf parameters
    parser.add_argument(
        "--export-all",
        action="store_true",
        help="PDF only: export every non-empty object and skip heuristics/YARA/entropy.",
    )
    parser.add_argument("--win-size", type=int, default=None, help=f"PDF only: sliding entropy window size. Default: {WINDOW_SIZE}")
    parser.add_argument("--win-step", type=int, default=None, help=f"PDF only: sliding entropy window step. Default: {WINDOW_STEP}")
    parser.add_argument("--win-thr", type=float, default=None, help=f"PDF only: sliding entropy threshold. Default: {WINDOW_THR}")
    parser.add_argument("--whole-thr", type=float, default=None, help=f"PDF only: whole-stream entropy threshold. Default: {WHOLE_ENTROPY_THR}")
    parser.add_argument("--write-hex", action="store_true", help="PDF only: also write .hex/.dec.hex files.")

    # word/excel parameters
    parser.add_argument("--max", "-m", dest="max_size", type=int, default=None, help=f"Office only: max bytes per slice. Default: {MAX_SLICE_SIZE}")
    parser.add_argument("--stride", type=int, default=None, help=f"Office only: byte stride between slices. Default: {SLICE_STRIDE}")
    parser.add_argument("--entropy", "-e", type=float, default=None, help=f"Office only: entropy threshold. Default: {ENTROPY_THRESHOLD}")
    parser.add_argument("--kdthr", "-k", type=float, default=None, help=f"Office only: keyword-density threshold. Default: {KEYWORD_DENSITY_THRESHOLD}")
    parser.add_argument("--keywords", "-K", nargs="+", default=None, help=f"Office only: suspicious keywords. Default: {', '.join(KEYWORDS[:5])}, ...")
    parser.add_argument("--no-hex", action="store_true", help="Office only: do not write .hex files.")

    return parser


def _ensure_input_exists(path: Path) -> None:
    if not path.exists():
        raise FileNotFoundError(f"Input path does not exist: {path}")


def _ensure_rules_when_needed(rules: Path, export_all: bool, is_pdf: bool) -> None:
    # PDF export-all explicitly skips YARA. Everything else needs a rules path.
    if is_pdf and export_all:
        return
    if not rules.exists():
        raise FileNotFoundError(f"YARA rules path does not exist: {rules}")


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    file_type = args.file_type.lower()
    is_pdf = file_type in PDF_TYPES

    try:
        input_path = args.input.expanduser().resolve()
        out_dir = args.out.expanduser().resolve()
        rules_path = args.rules.expanduser().resolve()

        _ensure_input_exists(input_path)
        _ensure_rules_when_needed(rules_path, args.export_all, is_pdf)

        if is_pdf:
            from utils.slice_pdf import run_pdf_slicer

            pdf_kwargs = {}
            if args.win_size is not None:
                pdf_kwargs["win_size"] = args.win_size
            if args.win_step is not None:
                pdf_kwargs["win_step"] = args.win_step
            if args.win_thr is not None:
                pdf_kwargs["win_thr"] = args.win_thr
            if args.whole_thr is not None:
                pdf_kwargs["whole_thr"] = args.whole_thr

            run_pdf_slicer(
                input_path=input_path,
                out_root=out_dir,
                rules_path=rules_path,
                recursive=args.recursive,
                export_all=args.export_all,
                write_hex=args.write_hex,
                **pdf_kwargs,
            )
        else:
            from utils.slice_doc_xls import run_office_slicer

            if args.export_all:
                print("[!] --export-all is PDF-only and will be ignored for Office files.", file=sys.stderr)

            office_type = "office"
            if file_type in WORD_TYPES:
                office_type = "word"
            elif file_type in EXCEL_TYPES:
                office_type = "excel"

            office_kwargs = {}
            if args.max_size is not None:
                office_kwargs["max_size"] = args.max_size
            if args.stride is not None:
                office_kwargs["stride"] = args.stride
            if args.entropy is not None:
                office_kwargs["entropy_thr"] = args.entropy
            if args.kdthr is not None:
                office_kwargs["kd_thr"] = args.kdthr
            if args.keywords is not None:
                office_kwargs["keywords"] = args.keywords

            run_office_slicer(
                input_path=input_path,
                out_dir=out_dir,
                rules_path=rules_path,
                file_type=office_type,
                recursive=args.recursive,
                write_hex=not args.no_hex,
                **office_kwargs,
            )

        return 0

    except Exception as exc:
        print(f"[x] {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
