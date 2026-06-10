"""
General configuration of parameters.

This file keeps the slicer constants expected by the extraction utilities and
adds one coherent scoring configuration surface for OpenAI, OpenAI-compatible
local Llama endpoints, native Ollama, and llama-cpp.
"""

from __future__ import annotations

from dataclasses import dataclass
import os
from typing import Tuple


def _env(key: str, default: str) -> str:
    return os.getenv(key, default)


def _env_int(key: str, default: int) -> int:
    try:
        return int(os.getenv(key, str(default)))
    except ValueError:
        return default


def _env_float(key: str, default: float) -> float:
    try:
        return float(os.getenv(key, str(default)))
    except ValueError:
        return default


@dataclass
class LLMConfig:
    # OpenAI-compatible local endpoint. For native Ollama, use OllamaConfig.
    base_url: str = _env("LLAMA_API", "http://localhost:11434/v1/chat/completions")
    model: str = _env("LLAMA_MODEL", "llama-3-8b-instruct")
    temperature: float = _env_float("LLM_TEMPERATURE", 0.0)
    timeout: int = _env_int("LLM_TIMEOUT", 30)
    retries: int = _env_int("LLM_RETRIES", 3)
    backoff: float = _env_float("LLM_BACKOFF", 2.0)
    batch: int = _env_int("LLM_BATCH", 8)  # slices/per request


@dataclass
class OpenAIConfig:
    model: str = _env("OPENAI_MODEL", "gpt-4o-mini")
    api_key_env: str = "OPENAI_API_KEY"
    base_url: str = _env("OPENAI_BASE_URL", "")  # blank means official OpenAI API
    temperature: float = _env_float("OPENAI_TEMPERATURE", 0.0)
    timeout: int = _env_int("OPENAI_TIMEOUT", 60)
    retries: int = _env_int("OPENAI_RETRIES", 2)


@dataclass
class OllamaConfig:
    model: str = _env("OLLAMA_MODEL", "llama3")
    url: str = _env("OLLAMA_URL", "http://localhost:11434/api/chat")
    temperature: float = _env_float("OLLAMA_TEMPERATURE", 0.0)
    timeout: int = _env_int("OLLAMA_TIMEOUT", 120)
    retries: int = _env_int("OLLAMA_RETRIES", 1)


@dataclass
class LlamaCppConfig:
    model_path: str = _env("LLAMA_CPP_MODEL_PATH", "")
    chat_format: str = _env("LLAMA_CPP_CHAT_FORMAT", "llama-3")
    n_ctx: int = _env_int("LLAMA_CPP_N_CTX", 8192)
    n_gpu_layers: int = _env_int("LLAMA_CPP_N_GPU_LAYERS", -1)


@dataclass
class ScoringConfig:
    default_backend: str = _env("SCORING_BACKEND", "ollama")
    max_hex_lines: int = _env_int("SCORING_MAX_HEX_LINES", 300)
    max_prompt_chars: int = _env_int("SCORING_MAX_PROMPT_CHARS", 32_000)
    max_response_tokens: int = _env_int("SCORING_MAX_RESPONSE_TOKENS", 128)
    size_dampen_threshold: int = _env_int("SCORING_SIZE_DAMPEN_THRESHOLD", 20_000)
    decoded_snip_limit: int = _env_int("SCORING_DECODED_SNIP_LIMIT", 4)
    decoded_snip_max_bytes: int = _env_int("SCORING_DECODED_SNIP_MAX_BYTES", 1024)
    output_prefix: str = _env("SCORING_OUTPUT_PREFIX", "results")


@dataclass
class SlicerConfig:
    high_entropy: float = 7.2  # bits/byte
    win: int = 4096
    stride: int = 1024
    adapt: int = 2048
    adapt_stride: int = 512


# backward-compatible aliases
APIConfig = LLMConfig
ChunkConfig = SlicerConfig

_SLICER = SlicerConfig()
_SCORE = ScoringConfig()

# Project paths / YARA
RULES_DIR: str = "rules"
UPDATED_RULES_FILE: str = os.path.join(RULES_DIR, "updated_rules.yar")
DEFAULT_RULES_PATH: str = UPDATED_RULES_FILE
YARA_RULES_DIR: str = RULES_DIR

# common slicer thresholds
ENTROPY_THRESHOLD: float = _SLICER.high_entropy
MAX_SLICE_SIZE: int = _SLICER.adapt
SLICE_STRIDE: int = _SLICER.adapt_stride
KEYWORD_DENSITY_THRESHOLD: float = 0.05

# --------
# pdf slicer constants for utils/slice_pdf.py
# --------
WINDOW_SIZE: int = _SLICER.win
WINDOW_STEP: int = _SLICER.stride
WINDOW_THR: float = _SLICER.high_entropy
WHOLE_ENTROPY_THR: float = _SLICER.high_entropy
MAX_WIN_HITS_LOG: int = 32
RECURSIVE_DEFAULT: bool = False
EXPORT_ALL_DEFAULT: bool = False
BIN_SUBDIR: str = "bin"
HEX_SUBDIR: str = "hex"
GLOBAL_JSON_NAME: str = "metadata.json"

SUSPICIOUS_KEYS: Tuple[str, ...] = (
    "/JavaScript",
    "/JS",
    "Screen",
    "Movie",
    "Sound",
    "3D",
    "U3D",
    "JBIG2Decode",
    "JBIG2Globals",
    "GoToE",
    "GoToR",
    "/OpenAction",
    "/AA",
    "/Launch",
    "/EmbeddedFile",
    "/RichMedia",
    "/AcroForm",
    "/XFA",
    "/ObjStm",
    "/Encrypt",
)

# --------
# word / excel slicer constants for utils/slice_doc_xls.py
# --------
KEYWORDS: Tuple[str, ...] = (
    "autoopen",
    "document_open",
    "workbook_open",
    "shell",
    "powershell",
    "cmd.exe",
    "wscript",
    "cscript",
    "vba",
    "createobject",
    "urlmon",
    "winhttp",
    "xmlhttp",
)

WORD_EXTENSIONS: Tuple[str, ...] = (".doc", ".docx", ".docm")
EXCEL_EXTENSIONS: Tuple[str, ...] = (".xls", ".xlsx", ".xlsm")
OLE_EXTENSIONS: Tuple[str, ...] = (".doc", ".xls")
FILE_EXTENSIONS: Tuple[str, ...] = WORD_EXTENSIONS + EXCEL_EXTENSIONS
OFFICE_EXTENSIONS: Tuple[str, ...] = FILE_EXTENSIONS

SLIDING_WINDOW_OVERLAP: float = 0.5
OOXML_DOC_PATH: str = "word/document.xml"
WORD_NAMESPACE: str = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
OOXML_SHEETS_PREFIX: str = "xl/worksheets/"
OOXML_VBA_PATHS: Tuple[str, ...] = ("word/vbaProject.bin", "xl/vbaProject.bin")

# scoring constants used by the unified scorer
SCORING_SYSTEM_PROMPT: str = (
    "You are a defensive malware-analysis assistant. Reply on ONE line only:\n"
    "<probability 0-1> - <very short reason>.\n"
    "High entropy ALONE does NOT imply malware; consider structural indicators, "
    "decoded artefacts, suspicious API names, macros, launch actions, embedded files, "
    "and known benign false positives. Do not write exploit code or instructions."
)
SCORING_DEFAULT_BACKEND: str = _SCORE.default_backend
SCORING_MAX_HEX_LINES: int = _SCORE.max_hex_lines
SCORING_MAX_PROMPT_CHARS: int = _SCORE.max_prompt_chars
SCORING_MAX_RESPONSE_TOKENS: int = _SCORE.max_response_tokens
SCORING_SIZE_DAMPEN_THRESHOLD: int = _SCORE.size_dampen_threshold
SCORING_DECODED_SNIP_LIMIT: int = _SCORE.decoded_snip_limit
SCORING_DECODED_SNIP_MAX_BYTES: int = _SCORE.decoded_snip_max_bytes
SCORING_OUTPUT_PREFIX: str = _SCORE.output_prefix
