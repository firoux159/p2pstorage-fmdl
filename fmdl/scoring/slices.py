from __future__ import annotations

import json
from glob import glob
from pathlib import Path
from typing import Any, Iterable, List, Optional

from .types import SliceFiles, SliceRecord


DEFAULT_METADATA_NAMES = ("slices.json", "metadata.json")


def load_slice_records(slices_dir: Path, metadata_name: str = "auto") -> tuple[Path, List[SliceRecord]]:
    """Load slice metadata from slices.json or metadata.json."""
    if metadata_name == "auto":
        candidates = [slices_dir / name for name in DEFAULT_METADATA_NAMES]
        found = next((p for p in candidates if p.exists()), None)
        if found is None:
            raise FileNotFoundError(
                f"no slice metadata found in {slices_dir}; expected one of: "
                + ", ".join(DEFAULT_METADATA_NAMES)
            )
        metadata_path = found
    else:
        metadata_path = slices_dir / metadata_name
        if not metadata_path.exists():
            raise FileNotFoundError(f"metadata file not found: {metadata_path}")

    raw = json.loads(metadata_path.read_text(encoding="utf-8"))
    if not isinstance(raw, list):
        raise ValueError(f"{metadata_path} must contain a JSON array")
    if not all(isinstance(item, dict) for item in raw):
        raise ValueError(f"{metadata_path} must contain an array of objects")
    return metadata_path, raw


def _candidate_paths(root: Path, rel: str, subdir: str | None = None) -> Iterable[Path]:
    p = Path(rel)
    if p.is_absolute():
        yield p
        return
    yield root / p
    if subdir:
        yield root / subdir / p


def _first_existing(candidates: Iterable[Path]) -> Optional[Path]:
    for candidate in candidates:
        if candidate and candidate.exists() and candidate.is_file():
            return candidate
    return None


def _nested_file(record: SliceRecord, key: str) -> Optional[str]:
    files = record.get("files")
    if isinstance(files, dict):
        val = files.get(key)
        if val:
            return str(val)
    return None


def resolve_slice_files(record: SliceRecord, slices_dir: Path) -> SliceFiles:
    """
    Resolve bin/hex files, for 
    - top-level bin/bin_file/binpath and hex/hex_file/hexpath
    - nested files.bin/files.hex from the PDF slicer metadata
    - fallback glob: <obj>*.bin / <obj>*.hex in root, bin/, and hex/
    """
    base = str(record.get("obj") or record.get("id") or "")
    if not base:
        raise KeyError("slice record has no obj/id field")

    bin_rel = None
    for key in ("bin_file", "bin", "binpath"):
        if record.get(key):
            bin_rel = str(record[key])
            break
    bin_rel = bin_rel or _nested_file(record, "bin")

    hex_rel = None
    for key in ("hex_file", "hex", "hexpath"):
        if record.get(key):
            hex_rel = str(record[key])
            break
    hex_rel = hex_rel or _nested_file(record, "hex")

    bin_path = None
    if bin_rel:
        bin_path = _first_existing(_candidate_paths(slices_dir, bin_rel, "bin"))
    if bin_path is None:
        patterns = [
            slices_dir / f"{base}*.bin",
            slices_dir / "bin" / f"{base}*.bin",
            slices_dir / "**" / f"{base}*.bin",
        ]
        matches: list[str] = []
        for pat in patterns:
            matches.extend(glob(str(pat), recursive=True))
        if matches:
            bin_path = Path(sorted(set(matches))[0])
    if bin_path is None:
        raise KeyError(f"no .bin file found for slice {base!r}")

    hex_path = None
    if hex_rel:
        hex_path = _first_existing(_candidate_paths(slices_dir, hex_rel, "hex"))
    if hex_path is None:
        patterns = [
            slices_dir / f"{base}*.hex",
            slices_dir / "hex" / f"{base}*.hex",
            slices_dir / "**" / f"{base}*.hex",
        ]
        matches = []
        for pat in patterns:
            matches.extend(glob(str(pat), recursive=True))
        if matches:
            hex_path = Path(sorted(set(matches))[0])

    return SliceFiles(bin_path=bin_path, hex_path=hex_path)
