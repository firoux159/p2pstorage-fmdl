from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Sequence, Tuple

import config
from .types import Message, SliceRecord


_CANONICAL_TYPES: Dict[str, str] = {
    "pdf": "pdf",
    ".pdf": "pdf",
    "word": "word",
    "doc": "word",
    "docx": "word",
    "docm": "word",
    ".doc": "word",
    ".docx": "word",
    ".docm": "word",
    "excel": "excel",
    "xls": "excel",
    "xlsx": "excel",
    "xlsm": "excel",
    ".xls": "excel",
    ".xlsx": "excel",
    ".xlsm": "excel",
}


DEFAULT_EXAMPLE_FILENAMES: Dict[str, str] = {
    "pdf": "pdf_examples.json",
    "word": "word_examples.json",
    "excel": "excel_examples.json",
}


def project_root() -> Path:
    # return the repository/package root containing config.py and score_slices.py
    return Path(__file__).resolve().parents[1]


def canonical_file_type(value: str | None) -> str | None:
    if value is None:
        return None
    return _CANONICAL_TYPES.get(str(value).strip().lower())


def normalize_examples(raw: Any) -> List[Message]:
    """
    this is to acccept any of the following JSON shapes:
    1. OpenAI/Ollama messages: [{"role":"user","content":"..."}, ...]
    2. Pair objects: [{"user":"...", "assistant":"..."}, ...]
    3. Two-item arrays: [["user text", "assistant text"], ...]
    returns a flat list of chat messages. Empty examples are allowed.
    """
    if raw in (None, ""):
        return []
    if not isinstance(raw, list):
        raise ValueError("examples must be a JSON array")

    out: List[Message] = []
    for i, item in enumerate(raw):
        if isinstance(item, dict) and "role" in item and "content" in item:
            role = str(item["role"])
            if role not in {"system", "user", "assistant"}:
                raise ValueError(f"examples[{i}] has invalid role: {role!r}")
            out.append({"role": role, "content": str(item["content"])})
            continue

        if isinstance(item, dict) and "user" in item and "assistant" in item:
            out.append({"role": "user", "content": str(item["user"])})
            out.append({"role": "assistant", "content": str(item["assistant"])})
            continue

        if isinstance(item, (list, tuple)) and len(item) == 2:
            out.append({"role": "user", "content": str(item[0])})
            out.append({"role": "assistant", "content": str(item[1])})
            continue

        raise ValueError(
            f"examples[{i}] must be a message dict, user/assistant dict, or 2-item pair"
        )

    validate_example_turns(out)
    return out


def validate_example_turns(messages: Sequence[Message]) -> None:
    """
    hanldes useless examples early. A single orphan user/assistant message is almost
    always a malformed few-shot file, and silently accepting it poisons scoring.
    """
    non_system = [m for m in messages if m.get("role") != "system"]
    if not non_system:
        return
    if len(non_system) % 2:
        raise ValueError("few-shot examples must have complete user/assistant turns")
    for i in range(0, len(non_system), 2):
        if non_system[i].get("role") != "user" or non_system[i + 1].get("role") != "assistant":
            raise ValueError(
                "few-shot examples must alternate user then assistant messages after any system messages"
            )


def load_examples(path: Path | None) -> List[Message]:
    if path is None:
        return []
    try:
        return normalize_examples(json.loads(path.read_text(encoding="utf-8")))
    except Exception as exc:
        raise ValueError(f"invalid examples file {path}: {exc}") from exc


def _record_text_values(record: SliceRecord) -> List[str]:
    vals: List[str] = []
    for key in (
        "filetype",
        "file_type",
        "doc_type",
        "type",
        "source_type",
        "source",
        "input",
        "path",
        "filename",
        "file",
        "pdf",
        "obj",
        "id",
    ):
        val = record.get(key)
        if val:
            vals.append(str(val))
    files = record.get("files")
    if isinstance(files, dict):
        vals.extend(str(v) for v in files.values() if v)
    triggers = record.get("triggers")
    if isinstance(triggers, list):
        vals.extend(str(t) for t in triggers)
    elif triggers:
        vals.append(str(triggers))
    return vals


def infer_file_type(records: Sequence[SliceRecord], slices_dir: Path | None = None) -> str | None:
    # infer pdf/word/excel from metadata, file paths, triggers, or directory name."""
    for record in records:
        for text in _record_text_values(record):
            lowered = text.lower()
            # Direct extensions/labels first.
            for ext, typ in (
                (".pdf", "pdf"),
                (".docm", "word"),
                (".docx", "word"),
                (".doc", "word"),
                (".xlsm", "excel"),
                (".xlsx", "excel"),
                (".xls", "excel"),
            ):
                if ext in lowered:
                    return typ
            direct = canonical_file_type(lowered)
            if direct:
                return direct
            # Structural hints common in extracted Office slices.
            if "word/" in lowered or "word\\" in lowered or "worddocument" in lowered:
                return "word"
            if "xl/" in lowered or "workbook" in lowered or "worksheet" in lowered:
                return "excel"
            if lowered.startswith("key:/") or "/javascript" in lowered or "/openaction" in lowered:
                return "pdf"

    if slices_dir is not None:
        for part in reversed(slices_dir.parts):
            direct = canonical_file_type(part)
            if direct:
                return direct
            lowered = part.lower()
            if "pdf" in lowered:
                return "pdf"
            if any(x in lowered for x in ("word", "docx", "docm", "doc")):
                return "word"
            if any(x in lowered for x in ("excel", "xlsx", "xlsm", "xls")):
                return "excel"
    return None


def resolve_few_shot_dir(path: Path | None = None) -> Path:
    if path is not None:
        return path
    configured = Path(config.SCORING_FEW_SHOT_DIR)
    if configured.is_absolute():
        return configured
    return project_root() / configured


def default_examples_path(file_type: str, few_shot_dir: Path | None = None) -> Path:
    typ = canonical_file_type(file_type)
    if typ is None:
        raise ValueError(f"unsupported file type for few-shot examples: {file_type!r}")
    return resolve_few_shot_dir(few_shot_dir) / DEFAULT_EXAMPLE_FILENAMES[typ]


def load_default_examples(file_type: str, few_shot_dir: Path | None = None) -> Tuple[List[Message], Path]:
    path = default_examples_path(file_type, few_shot_dir)
    if not path.exists():
        raise FileNotFoundError(f"few-shot examples not found for {file_type}: {path}")
    return load_examples(path), path
