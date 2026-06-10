"""
LLM scorer for PDF/Word/Excel slices using OpenAI, local Llama endpoints, Ollama, or llama-cpp
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Tuple

import config
from scoring.clients import build_client
from scoring.examples import (
    canonical_file_type,
    infer_file_type,
    load_default_examples,
    load_examples,
)
from scoring.pipeline import score_directory
from scoring.slices import load_slice_records
from scoring.types import Message


def build_parser() -> argparse.ArgumentParser:
    cfg = config.ScoringConfig()
    ap = argparse.ArgumentParser(
        prog="score_slices.py",
        description="Score malware-analysis slices with one unified OpenAI/Llama pipeline.",
    )
    ap.add_argument("slices_dir", type=Path, help="Directory containing slices.json or metadata.json and slice files")
    ap.add_argument(
        "-e",
        "--examples",
        type=Path,
        help="Custom few-shot examples JSON. Overrides data/few-shot auto-selection.",
    )
    ap.add_argument(
        "--file-type",
        choices=("auto", "pdf", "word", "doc", "docx", "docm", "excel", "xls", "xlsx", "xlsm", "none"),
        default="auto",
        help="Few-shot family to use. Default: auto-detect from metadata/path. Use 'none' to disable.",
    )
    ap.add_argument(
        "--few-shot-dir",
        type=Path,
        help=f"Directory containing pdf_examples.json, word_examples.json, excel_examples.json. Default: {cfg.few_shot_dir}",
    )
    ap.add_argument("--no-few-shot", action="store_true", help="Disable default few-shot examples")
    ap.add_argument(
        "--metadata",
        default="auto",
        help="Metadata filename. Default: auto-detect slices.json then metadata.json",
    )
    ap.add_argument(
        "--backend",
        choices=("openai", "openai-compatible", "ollama", "llama-cpp"),
        default=cfg.default_backend,
        help=f"Scoring backend. Default from config/env: {cfg.default_backend}",
    )
    ap.add_argument("--model", help="Model name. Defaults depend on backend/config")
    ap.add_argument("--api-key", help="OpenAI API key. Prefer OPENAI_API_KEY env var")
    ap.add_argument("--base-url", help="OpenAI or OpenAI-compatible chat completions URL")
    ap.add_argument("--ollama-url", help="Native Ollama /api/chat URL")
    ap.add_argument("--model-path", type=Path, help="GGUF model path for --backend llama-cpp")
    ap.add_argument("--chat-format", help="llama-cpp-python chat_format, e.g. llama-3")
    ap.add_argument("--n-ctx", type=int, help="llama-cpp context window")
    ap.add_argument("--n-gpu-layers", type=int, help="llama-cpp GPU layers; -1 means all possible")
    ap.add_argument("--temperature", type=float, help="Model temperature")
    ap.add_argument("--timeout", type=int, help="Request timeout seconds")
    ap.add_argument("--retries", type=int, help="Retry count")
    ap.add_argument("--max-response-tokens", type=int, default=cfg.max_response_tokens)
    ap.add_argument("--max-hex-lines", type=int, default=cfg.max_hex_lines)
    ap.add_argument("--max-prompt-chars", type=int, default=cfg.max_prompt_chars)
    ap.add_argument("--decoded-limit", type=int, default=cfg.decoded_snip_limit)
    ap.add_argument("--decoded-max-bytes", type=int, default=cfg.decoded_snip_max_bytes)
    ap.add_argument("--size-dampen-threshold", type=int, default=cfg.size_dampen_threshold)
    ap.add_argument("--include-meta", action="store_true", help="Include size/triggers metadata in prompts")
    ap.add_argument("--output-prefix", default=cfg.output_prefix, help="Output prefix, relative to slices_dir unless absolute")
    ap.add_argument("--limit", type=int, help="Score only the first N records")
    ap.add_argument("--fail-fast", action="store_true")
    ap.add_argument("--verbose", action="store_true")
    return ap


def _select_examples(args: argparse.Namespace) -> Tuple[list[Message], str, str]:
    """
    Return (messages, source, resolved_file_type).
    Precedence:
    1. --no-few-shot or --file-type none -> no examples
    2. --examples -> user-supplied examples
    3. auto/default examples from data/few-shot/<type>_examples.json
    """
    if args.no_few_shot or args.file_type == "none":
        return [], "disabled", "none"

    if args.examples:
        return load_examples(args.examples), str(args.examples), "custom"

    metadata_path, records = load_slice_records(args.slices_dir, args.metadata)
    requested = canonical_file_type(args.file_type)
    resolved = requested if args.file_type != "auto" else infer_file_type(records, args.slices_dir)
    if not resolved:
        raise ValueError(
            "could not infer slice file type for few-shot examples. "
            "Pass --file-type pdf|word|excel, pass --examples, or use --no-few-shot. "
            f"Metadata inspected: {metadata_path}"
        )

    messages, path = load_default_examples(resolved, args.few_shot_dir)
    return messages, str(path), resolved


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        examples, examples_source, examples_file_type = _select_examples(args)
        client = build_client(args)
        rows, skipped, csv_path, jsonl_path = score_directory(
            slices_dir=args.slices_dir,
            client=client,
            examples=examples,
            examples_source=examples_source,
            examples_file_type=examples_file_type,
            metadata_name=args.metadata,
            include_meta=args.include_meta,
            max_hex_lines=args.max_hex_lines,
            max_prompt_chars=args.max_prompt_chars,
            decoded_limit=args.decoded_limit,
            decoded_max_bytes=args.decoded_max_bytes,
            size_dampen_threshold=args.size_dampen_threshold,
            output_prefix=args.output_prefix,
            verbose=args.verbose,
            limit=args.limit,
            fail_fast=args.fail_fast,
        )
    except Exception as exc:
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 2

    print(f"✓ Few-shot -> {examples_source} ({len(examples)} message(s), file_type={examples_file_type})")
    print(f"✓ Scored {len(rows)} slice(s)")
    print(f"✓ CSV   -> {csv_path}")
    print(f"✓ JSONL -> {jsonl_path}")
    if skipped:
        print(f"Skipped {skipped} slice(s); rerun with --verbose or --fail-fast to debug.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
