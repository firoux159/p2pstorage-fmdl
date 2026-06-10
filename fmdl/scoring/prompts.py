from __future__ import annotations

import re
from typing import List, Tuple

import config
from .types import Message, SliceRecord


SCORE_RE = re.compile(r"(?<!\d)(0(?:\.\d+)?|1(?:\.0+)?)(?!\d)\s*(?:[-–—:]\s*)?(.*)")


def message_chars(messages: List[Message]) -> int:
    return sum(len(m.get("content", "")) + 16 for m in messages)


def _metadata_filetype(record: SliceRecord) -> str:
    for key in ("filetype", "file_type", "doc_type", "source_type"):
        if record.get(key):
            return str(record[key])
    if record.get("pdf"):
        return "pdf"
    return "unknown"


def build_prompt(
    hexblock: str,
    decoded: List[str],
    record: SliceRecord,
    include_meta: bool,
) -> str:
    parts: List[str] = []
    if include_meta:
        triggers = record.get("triggers") or []
        if not isinstance(triggers, list):
            triggers = [str(triggers)]
        parts.append(
            "Meta: "
            f"filetype={_metadata_filetype(record)} "
            f"obj={record.get('obj', record.get('id', '?'))} "
            f"size={record.get('size', '?')}B "
            f"stream={record.get('stream', '?')} "
            f"triggers={','.join(map(str, triggers)) or 'none'}"
        )

    parts.append("Hexdump:\n" + hexblock)
    if decoded:
        parts.append("Decoded artefact candidates:\n" + "\n\n".join(decoded))
    return "\n\n".join(parts)


def make_messages(prompt: str, examples: List[Message]) -> List[Message]:
    return [{"role": "system", "content": config.SCORING_SYSTEM_PROMPT}] + examples + [
        {"role": "user", "content": prompt}
    ]


def trim_messages(
    *,
    hex_lines: List[str],
    decoded: List[str],
    record: SliceRecord,
    examples: List[Message],
    include_meta: bool,
    max_prompt_chars: int,
) -> Tuple[List[Message], List[str]]:
    current_hex = list(hex_lines)
    current_decoded = list(decoded)
    prompt = build_prompt("\n".join(current_hex), current_decoded, record, include_meta)
    messages = make_messages(prompt, examples)

    while message_chars(messages) > max_prompt_chars and len(current_hex) > 50:
        current_hex = current_hex[:-50]
        prompt = build_prompt("\n".join(current_hex), current_decoded, record, include_meta)
        messages = make_messages(prompt, examples)

    if message_chars(messages) > max_prompt_chars and current_decoded:
        current_decoded = []
        current_hex = current_hex[:50]
        prompt = build_prompt("\n".join(current_hex), current_decoded, record, include_meta)
        messages = make_messages(prompt, examples)

    while message_chars(messages) > max_prompt_chars and len(current_hex) > 5:
        current_hex = current_hex[:-5]
        prompt = build_prompt("\n".join(current_hex), current_decoded, record, include_meta)
        messages = make_messages(prompt, examples)

    if message_chars(messages) > max_prompt_chars:
        raise ValueError(
            "prompt still exceeds max_prompt_chars after trimming; reduce few-shot examples "
            "or raise --max-prompt-chars"
        )

    return messages, current_hex


def parse_score_reason(text: str) -> tuple[float, str]:
    one_line = " ".join(text.strip().splitlines())
    match = SCORE_RE.search(one_line)
    if not match:
        return -1.0, one_line[:500]
    try:
        score = float(match.group(1))
    except ValueError:
        return -1.0, one_line[:500]
    score = max(0.0, min(1.0, score))
    reason = match.group(2).strip() or one_line[:500]
    return score, reason[:500]
