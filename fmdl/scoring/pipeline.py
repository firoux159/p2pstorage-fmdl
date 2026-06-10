from __future__ import annotations

import csv
import json
from itertools import islice
from pathlib import Path
from typing import Any, Dict, List

import config
from .clients import ScoringClient
from .decoding import decoded_snips, hexdump
from .prompts import parse_score_reason, trim_messages
from .slices import load_slice_records, resolve_slice_files
from .types import Message, ScoreResult, SliceRecord


def _read_hex_lines(hex_path: Path | None, blob: bytes, max_hex_lines: int) -> List[str]:
    if hex_path is not None and hex_path.exists():
        return list(islice(hex_path.read_text(encoding="utf-8", errors="replace").splitlines(), max_hex_lines))
    return hexdump(blob, max_lines=max_hex_lines).splitlines()


def _flatten_for_csv(row: Dict[str, Any]) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    for key, value in row.items():
        if isinstance(value, (dict, list, tuple)):
            out[key] = json.dumps(value, ensure_ascii=False)
        else:
            out[key] = value
    return out


def write_outputs(results: List[Dict[str, Any]], output_prefix: Path) -> tuple[Path, Path]:
    csv_path = output_prefix.with_suffix(".csv")
    jsonl_path = output_prefix.with_suffix(".jsonl")
    csv_path.parent.mkdir(parents=True, exist_ok=True)

    with jsonl_path.open("w", encoding="utf-8") as f:
        for row in results:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    fieldnames: List[str] = []
    for row in results:
        for key in row.keys():
            if key not in fieldnames:
                fieldnames.append(key)

    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for row in results:
            writer.writerow(_flatten_for_csv(row))

    return csv_path, jsonl_path


def score_directory(
    *,
    slices_dir: Path,
    client: ScoringClient,
    examples: List[Message],
    examples_source: str = "none",
    examples_file_type: str = "none",
    metadata_name: str = "auto",
    include_meta: bool = False,
    max_hex_lines: int = config.SCORING_MAX_HEX_LINES,
    max_prompt_chars: int = config.SCORING_MAX_PROMPT_CHARS,
    decoded_limit: int = config.SCORING_DECODED_SNIP_LIMIT,
    decoded_max_bytes: int = config.SCORING_DECODED_SNIP_MAX_BYTES,
    size_dampen_threshold: int = config.SCORING_SIZE_DAMPEN_THRESHOLD,
    output_prefix: str = config.SCORING_OUTPUT_PREFIX,
    verbose: bool = False,
    limit: int | None = None,
    fail_fast: bool = False,
) -> tuple[List[Dict[str, Any]], int, Path, Path]:
    metadata_path, records = load_slice_records(slices_dir, metadata_name)
    if limit is not None:
        records = records[:limit]

    out_rows: List[Dict[str, Any]] = []
    skipped = 0

    for idx, record in enumerate(records, start=1):
        label = str(record.get("obj") or record.get("id") or f"slice_{idx}")
        try:
            files = resolve_slice_files(record, slices_dir)
            blob = files.bin_path.read_bytes()
            hex_lines = _read_hex_lines(files.hex_path, blob, max_hex_lines)
            decoded = decoded_snips(blob, limit=decoded_limit, max_each=decoded_max_bytes)
            messages, used_hex_lines = trim_messages(
                hex_lines=hex_lines,
                decoded=decoded,
                record=record,
                examples=examples,
                include_meta=include_meta,
                max_prompt_chars=max_prompt_chars,
            )
            raw = client.complete(messages)
            score, reason = parse_score_reason(raw)

            if (
                score > 0.7
                and not record.get("triggers")
                and int(record.get("size") or len(blob)) > size_dampen_threshold
            ):
                score *= 0.5
                reason = f"{reason} (dampened: size/randomness only)"

            row = dict(record)
            row.update(
                {
                    "score": score,
                    "reason": reason,
                    "raw_response": raw,
                    "backend": client.backend,
                    "model": client.model,
                    "metadata_file": metadata_path.name,
                    "bin_path": str(files.bin_path.relative_to(slices_dir)) if files.bin_path.is_relative_to(slices_dir) else str(files.bin_path),
                    "hex_path": str(files.hex_path.relative_to(slices_dir)) if files.hex_path and files.hex_path.is_relative_to(slices_dir) else (str(files.hex_path) if files.hex_path else "generated_from_bin"),
                    "prompt_hex_lines": len(used_hex_lines),
                    "few_shot_source": examples_source,
                    "few_shot_file_type": examples_file_type,
                    "few_shot_messages": len(examples),
                }
            )
            out_rows.append(row)

            if verbose:
                print(f"{label:>20}: {score:.3f} - {reason[:90]}")

        except Exception as exc:
            skipped += 1
            if verbose or fail_fast:
                print(f"[WARN] {label}: {exc}")
            if fail_fast:
                raise

    prefix_path = Path(output_prefix)
    if not prefix_path.is_absolute():
        prefix_path = slices_dir / output_prefix
    csv_path, jsonl_path = write_outputs(out_rows, prefix_path)
    return out_rows, skipped, csv_path, jsonl_path
