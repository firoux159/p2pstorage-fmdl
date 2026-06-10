"""
Context-aware Word/Excel slicer
"""

from __future__ import annotations

import argparse
import io
import json
import math
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

# allow direct execution
PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

import olefile  # pip install olefile
import yara     # pip install yara-python

try:
    from config import (
        DEFAULT_RULES_PATH,
        ENTROPY_THRESHOLD,
        MAX_SLICE_SIZE,
        SLICE_STRIDE,
        KEYWORD_DENSITY_THRESHOLD,
        KEYWORDS,
        OOXML_DOC_PATH,
        OOXML_SHEETS_PREFIX,
        OOXML_VBA_PATHS,
        WORD_EXTENSIONS,
        EXCEL_EXTENSIONS,
        OLE_EXTENSIONS,
        OFFICE_EXTENSIONS,
    )
except Exception: 
    DEFAULT_RULES_PATH = "rules/updated_rules.yar"
    ENTROPY_THRESHOLD = 7.2
    MAX_SLICE_SIZE = 2048
    SLICE_STRIDE = 512
    KEYWORD_DENSITY_THRESHOLD = 0.05
    KEYWORDS = (
        "autoopen", "document_open", "workbook_open", "shell", "powershell",
        "cmd.exe", "wscript", "cscript", "vba", "createobject", "urlmon",
        "winhttp", "xmlhttp",
    )
    OOXML_DOC_PATH = "word/document.xml"
    OOXML_SHEETS_PREFIX = "xl/worksheets/"
    OOXML_VBA_PATHS = ("word/vbaProject.bin", "xl/vbaProject.bin")
    WORD_EXTENSIONS = (".doc", ".docx", ".docm")
    EXCEL_EXTENSIONS = (".xls", ".xlsx", ".xlsm")
    OLE_EXTENSIONS = (".doc", ".xls")
    OFFICE_EXTENSIONS = WORD_EXTENSIONS + EXCEL_EXTENSIONS

WORD_EXTENSIONS: Set[str] = set(WORD_EXTENSIONS)
EXCEL_EXTENSIONS: Set[str] = set(EXCEL_EXTENSIONS)
OLE_EXTENSIONS: Set[str] = set(OLE_EXTENSIONS)
OFFICE_EXTENSIONS: Set[str] = set(OFFICE_EXTENSIONS)
OOXML_VBA_PATHS = tuple(OOXML_VBA_PATHS)
KEYWORDS = tuple(KEYWORDS)


# ---------------------------------------------------------------------------
# Utility functions
# ---------------------------------------------------------------------------
def compute_entropy(data: bytes) -> float:
    """Calculate Shannon entropy in bits per byte."""
    if not data:
        return 0.0
    counts = Counter(data)
    length = len(data)
    return -sum((cnt / length) * math.log2(cnt / length) for cnt in counts.values())


def keyword_density(data: bytes, keywords: Sequence[str]) -> float:
    """Compute fraction of decoded words containing suspicious keywords."""
    text = data.decode("utf-8", errors="ignore").lower().split()
    if not text:
        return 0.0
    lowered = [kw.lower() for kw in keywords]
    hits = sum(1 for word in text if any(kw in word for kw in lowered))
    return hits / len(text)


def slide_windows(data: bytes, window_size: int, stride: int) -> Iterable[bytes]:
    """Yield fixed-size windows using an explicit stride from config.SLICE_STRIDE."""
    if window_size <= 0:
        raise ValueError("window_size must be > 0")
    stride = max(1, int(stride))
    for start in range(0, len(data), stride):
        chunk = data[start:start + window_size]
        if chunk:
            yield chunk


def write_hexdump(data: bytes, file_path: Path) -> None:
    """Write an ASCII hexdump to file."""
    file_path.parent.mkdir(parents=True, exist_ok=True)
    with file_path.open("w", encoding="utf-8") as f:
        for i in range(0, len(data), 16):
            chunk = data[i:i + 16]
            hex_bytes = " ".join(f"{b:02x}" for b in chunk)
            ascii_rep = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
            f.write(f"{i:08x}  {hex_bytes:<48}  {ascii_rep}\n")


def compile_yara_rules(rules_path: str | Path) -> yara.Rules:
    """
    Compile/load YARA from either a directory, a source rule file, or a compiled bundle.
    """
    path = Path(rules_path).expanduser().resolve()
    if not path.exists():
        raise FileNotFoundError(f"YARA rules path not found: {path}")

    if path.is_dir():
        files = sorted(p for p in path.iterdir() if p.suffix.lower() in {".yar", ".yara"})
        if not files:
            raise FileNotFoundError(f"No .yar/.yara files found in {path}")
        filepaths = {p.stem: str(p) for p in files}
        return yara.compile(filepaths=filepaths)

    compile_exc: Optional[BaseException] = None
    try:
        return yara.compile(filepath=str(path))
    except Exception as exc:
        compile_exc = exc

    try:
        return yara.load(str(path))
    except Exception as load_exc:
        raise RuntimeError(
            f"Failed to compile or load YARA rules from {path}. "
            f"compile error: {compile_exc}; load error: {load_exc}"
        ) from load_exc


# ---------------------------------------------------------------------------
# Context extraction functions
# ---------------------------------------------------------------------------
def extract_ole_streams(path: str | Path) -> List[bytes]:
    """Extract every stream from a legacy OLE2 file (.doc/.xls or vbaProject.bin)."""
    contexts: List[bytes] = []
    with olefile.OleFileIO(str(path)) as ole:
        for stream in ole.listdir(streams=True, storages=False):
            try:
                contexts.append(ole.openstream(stream).read())
            except Exception:
                continue
    return contexts


def extract_ole_streams_from_bytes(data: bytes) -> List[bytes]:
    """Extract streams from a vbaProject.bin payload."""
    contexts: List[bytes] = []
    with olefile.OleFileIO(io.BytesIO(data)) as ole:
        for stream in ole.listdir(streams=True, storages=False):
            try:
                contexts.append(ole.openstream(stream).read())
            except Exception:
                continue
    return contexts


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def extract_ooxml_paragraphs(path: str | Path) -> List[bytes]:
    """Extract Word paragraph XML (<w:p>) from OOXML docx/docm."""
    contexts: List[bytes] = []
    with zipfile.ZipFile(path, "r") as zf:
        if OOXML_DOC_PATH not in zf.namelist():
            return contexts
        root = ET.fromstring(zf.read(OOXML_DOC_PATH))
        for node in root.iter():
            if _local_name(node.tag) == "p":
                contexts.append(ET.tostring(node, encoding="utf-8"))
    return contexts


def extract_ooxml_rows(path: str | Path) -> List[bytes]:
    """Extract worksheet row XML (<row>) from OOXML xlsx/xlsm."""
    contexts: List[bytes] = []
    with zipfile.ZipFile(path, "r") as zf:
        sheets = [n for n in zf.namelist() if n.startswith(OOXML_SHEETS_PREFIX) and n.endswith(".xml")]
        for sheet in sheets:
            try:
                root = ET.fromstring(zf.read(sheet))
            except Exception:
                continue
            for node in root.iter():
                if _local_name(node.tag) == "row":
                    contexts.append(ET.tostring(node, encoding="utf-8"))
    return contexts


def extract_vba_projects_from_ooxml(path: str | Path) -> List[bytes]:
    """Extract streams from embedded OOXML vbaProject.bin payloads."""
    contexts: List[bytes] = []
    try:
        with zipfile.ZipFile(path, "r") as zf:
            names = set(zf.namelist())
            for vba_path in OOXML_VBA_PATHS:
                if vba_path in names:
                    try:
                        contexts.extend(extract_ole_streams_from_bytes(zf.read(vba_path)))
                    except Exception:
                        continue
    except zipfile.BadZipFile:
        pass
    return contexts


# Backward-compatible function name from the uploaded script.
def extract_vba_project(data: bytes) -> List[bytes]:
    return extract_ole_streams_from_bytes(data)


# ---------------------------------------------------------------------------
# Stage 1: Combined/context-aware slicing
# ---------------------------------------------------------------------------
def generate_initial_slices(
    path: str | Path,
    max_size: int,
    entropy_thr: float,
    kd_thr: float,
    keywords: Sequence[str],
    stride: int = SLICE_STRIDE,
) -> Tuple[str, List[bytes]]:
    file_path = Path(path)
    base = file_path.stem
    ext = file_path.suffix.lower()
    contexts: List[bytes] = []

    if ext in OLE_EXTENSIONS:
        try:
            contexts.extend(extract_ole_streams(file_path))
        except Exception as exc:
            print(f"[!] Could not parse OLE streams from {file_path.name}: {exc}", file=sys.stderr)

    if ext in {".docx", ".docm"}:
        try:
            contexts.extend(extract_ooxml_paragraphs(file_path))
        except Exception as exc:
            print(f"[!] Could not parse OOXML paragraphs from {file_path.name}: {exc}", file=sys.stderr)

    if ext in {".xlsx", ".xlsm"}:
        try:
            contexts.extend(extract_ooxml_rows(file_path))
        except Exception as exc:
            print(f"[!] Could not parse OOXML rows from {file_path.name}: {exc}", file=sys.stderr)

    if ext in {".docm", ".xlsm", ".docx", ".xlsx"}:
        contexts.extend(extract_vba_projects_from_ooxml(file_path))

    # Do not silently produce zero slices. If structured extraction fails, scan
    # raw bytes in windows so YARA still gets a chance to hit.
    if not contexts:
        try:
            contexts.append(file_path.read_bytes())
        except Exception as exc:
            print(f"[!] Could not read raw bytes from {file_path}: {exc}", file=sys.stderr)

    initial: List[bytes] = []
    for ctx in contexts:
        ent = compute_entropy(ctx)
        kd = keyword_density(ctx, keywords)
        if len(ctx) <= max_size and ent < entropy_thr and kd < kd_thr:
            initial.append(ctx)
        else:
            initial.extend(slide_windows(ctx, max_size, stride=stride))
    return base, initial


# ---------------------------------------------------------------------------
# Stage 2 & 3: YARA filtering and output
# ---------------------------------------------------------------------------
def process_files(
    paths: Sequence[str | Path],
    out_dir: str | Path,
    max_size: int,
    entropy_thr: float,
    kd_thr: float,
    keywords: Sequence[str],
    yara_rules: yara.Rules,
    stride: int = SLICE_STRIDE,
    write_hex: bool = True,
) -> List[Dict[str, Any]]:
    out_dir = Path(out_dir).expanduser().resolve()
    bin_dir = out_dir / "bin"
    hex_dir = out_dir / "hex"
    bin_dir.mkdir(parents=True, exist_ok=True)
    if write_hex:
        hex_dir.mkdir(parents=True, exist_ok=True)

    records: List[Dict[str, Any]] = []

    for path_like in paths:
        path = Path(path_like)
        base, slices = generate_initial_slices(path, max_size, entropy_thr, kd_thr, keywords, stride=stride)
        matched = 0
        print(f"[*] {path.name}: generated {len(slices)} candidate slices")

        for idx, data in enumerate(slices):
            try:
                matches = yara_rules.match(data=data)
            except yara.Error as exc:
                print(f"[!] YARA scan failed for {path.name} slice {idx}: {exc}", file=sys.stderr)
                continue
            if not matches:
                continue

            matched += 1
            raw_name = f"{base}_{idx}.bin"
            raw_path = bin_dir / raw_name
            raw_path.write_bytes(data)

            hex_name: Optional[str] = None
            if write_hex:
                hex_name = f"{base}_{idx}.hex"
                write_hexdump(data, hex_dir / hex_name)

            records.append({
                "source": str(path),
                "obj": f"{base}_{idx}",
                "index": idx,
                "size": len(data),
                "rules": [m.rule for m in matches],
                "files": {
                    "bin": str(Path("bin") / raw_name),
                    "hex": str(Path("hex") / hex_name) if hex_name else None,
                },
            })

        print(f"[+] {path.name}: {matched} YARA-matching slices")

    meta_path = out_dir / "metadata.json"
    meta_path.write_text(json.dumps(records, indent=2), "utf-8")
    print(f"\n[✓] Finished. Metadata in: {meta_path}")
    return records


def _extensions_for_file_type(file_type: str) -> Set[str]:
    normalized = file_type.lower()
    if normalized in {"word", "doc"}:
        return WORD_EXTENSIONS
    if normalized in {"excel", "xls"}:
        return EXCEL_EXTENSIONS
    if normalized in {"office", "doc_xls", "word_excel"}:
        return OFFICE_EXTENSIONS
    raise ValueError(f"Unsupported Office file type: {file_type}")


def gather_office_files(input_path: str | Path, file_type: str = "office", recursive: bool = False) -> List[Path]:
    root = Path(input_path).expanduser().resolve()
    allowed = _extensions_for_file_type(file_type)

    if root.is_file():
        return [root] if root.suffix.lower() in allowed else []

    if root.is_dir():
        pattern = "**/*" if recursive else "*"
        return sorted(p for p in root.glob(pattern) if p.is_file() and p.suffix.lower() in allowed)

    return []


def run_office_slicer(
    input_path: str | Path,
    out_dir: str | Path,
    rules_path: str | Path,
    file_type: str = "office",
    recursive: bool = False,
    max_size: int = MAX_SLICE_SIZE,
    stride: int = SLICE_STRIDE,
    entropy_thr: float = ENTROPY_THRESHOLD,
    kd_thr: float = KEYWORD_DENSITY_THRESHOLD,
    keywords: Optional[Sequence[str]] = None,
    write_hex: bool = True,
) -> List[Dict[str, Any]]:
    """Callable Word/Excel slicer entrypoint for slice_wrapper.py."""
    keywords = list(keywords) if keywords is not None else list(KEYWORDS)
    files = gather_office_files(input_path, file_type=file_type, recursive=recursive)
    if not files:
        raise FileNotFoundError(f"No {file_type} files found under: {input_path}")

    yara_rules = compile_yara_rules(rules_path)
    return process_files(
        files,
        out_dir,
        max_size,
        entropy_thr,
        kd_thr,
        keywords,
        yara_rules,
        stride=stride,
        write_hex=write_hex,
    )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def main() -> None:
    parser = argparse.ArgumentParser(description="Word/Excel context-aware slicer with YARA filtering")
    parser.add_argument("input", help="File or directory to process")
    parser.add_argument("--out", "-o", default="out", help="Output directory")
    parser.add_argument("--type", "--file-type", default="office", choices=["word", "doc", "excel", "xls", "office"], help="Office file type to process")
    parser.add_argument("--recursive", action="store_true", help="Recurse into input subdirectories")
    parser.add_argument("--max", "-m", type=int, default=MAX_SLICE_SIZE, help="Max bytes per slice; default comes from config.MAX_SLICE_SIZE")
    parser.add_argument("--stride", type=int, default=SLICE_STRIDE, help="Stride in bytes; default comes from config.MAX_SLICE_SIZE_stride")
    parser.add_argument("--entropy", "-e", type=float, default=ENTROPY_THRESHOLD, help="Entropy threshold; default comes from config.ENTROPY_THRESHOLD")
    parser.add_argument("--kdthr", "-k", type=float, default=KEYWORD_DENSITY_THRESHOLD, help="Keyword density threshold")
    parser.add_argument("--keywords", "-K", nargs="+", default=KEYWORDS, help="Suspicious keywords")
    parser.add_argument("--rules", "-r", default=None, help="YARA source file, compiled bundle, or directory")
    parser.add_argument("--yara-dir", default=None, help="Backward-compatible alias for --rules")
    parser.add_argument("--no-hex", action="store_true", help="Do not write .hex files")
    args = parser.parse_args()

    rules_path = args.rules or args.yara_dir or DEFAULT_RULES_PATH
    run_office_slicer(
        input_path=args.input,
        out_dir=args.out,
        rules_path=rules_path,
        file_type=args.type,
        recursive=args.recursive,
        max_size=args.max,
        stride=args.stride,
        entropy_thr=args.entropy,
        kd_thr=args.kdthr,
        keywords=args.keywords,
        write_hex=not args.no_hex,
    )


if __name__ == "__main__":
    main()
