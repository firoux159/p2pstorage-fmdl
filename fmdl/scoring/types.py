from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional


Message = Dict[str, str]
SliceRecord = Dict[str, Any]


@dataclass(frozen=True)
class SliceFiles:
    bin_path: Path
    hex_path: Optional[Path] = None


@dataclass(frozen=True)
class ScoreResult:
    record: SliceRecord
    score: float
    reason: str
    raw_response: str
    backend: str
    model: str
