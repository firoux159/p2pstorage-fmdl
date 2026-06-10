"""
Context-aware PDF slicer utility
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from io import BytesIO
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

# allow direct execution
PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

import pikepdf
from pikepdf import PdfError
import yara
from tqdm import tqdm

try:
    from config import (
        WINDOW_SIZE,
        WINDOW_STEP,
        WINDOW_THR,
        WHOLE_ENTROPY_THR,
        MAX_WIN_HITS_LOG,
        GLOBAL_JSON_NAME,
        RECURSIVE_DEFAULT,
        EXPORT_ALL_DEFAULT,
        SUSPICIOUS_KEYS,
    )
except Exception: 
    WINDOW_SIZE = 4096
    WINDOW_STEP = 1024
    WINDOW_THR = 7.2
    WHOLE_ENTROPY_THR = 7.2
    MAX_WIN_HITS_LOG = 32
    GLOBAL_JSON_NAME = "metadata.json"
    RECURSIVE_DEFAULT = False
    EXPORT_ALL_DEFAULT = False
    SUSPICIOUS_KEYS = (
        "/JavaScript", "/JS", "/OpenAction", "/AA", "/Launch",
        "/EmbeddedFile", "/RichMedia", "/AcroForm", "/XFA", "/ObjStm", "/Encrypt",
    )


# ---------------------------------------------------------------------------
# Utilities
# ---------------------------------------------------------------------------
def shannon_entropy(data: bytes) -> float:
    if not data:
        return 0.0
    freq = [0] * 256
    for b in data:
        freq[b] += 1
    length = len(data)
    return -sum((c / length) * math.log2(c / length) for c in freq if c)


def hexdump(data: bytes, w: int = 16) -> str:
    lines = []
    for i in range(0, len(data), w):
        chunk = data[i:i + w]
        hexv = " ".join(f"{b:02X}" for b in chunk)
        asci = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        lines.append(f"{i:08X}  {hexv:<{w * 3}}  {asci}")
    return "\n".join(lines)


def is_visible(path: Path) -> bool:
    return not any(part.startswith(".") for part in path.parts)


def looks_like_pdf(path: Path) -> bool:
    return path.suffix.lower() == ".pdf" or path.suffix == ""


def safe_obj_bytes(obj: Any) -> bytes:
    """Serialize non-stream objects without decoding streams."""
    try:
        buf = BytesIO()
        pikepdf.write(buf, obj)
        return buf.getvalue()
    except Exception:
        try:
            return repr(obj).encode("utf-8", "replace")
        except Exception:
            return b""


def window_entropy(buf: bytes, size: int, step: int, thr: float) -> List[Tuple[int, int, float]]:
    if size <= 0 or step <= 0 or len(buf) < size:
        return []
    hits: List[Tuple[int, int, float]] = []
    for i in range(0, len(buf) - size + 1, step):
        chunk = buf[i:i + size]
        e = shannon_entropy(chunk)
        if e >= thr:
            hits.append((i, i + size, round(e, 2)))
    return hits


# ---------------------------------------------------------------------------
# YARA
# ---------------------------------------------------------------------------
def load_yara_rules(rule_file: Path | str | None) -> List[yara.Rules]:
    """Load YARA from a source rule file or a compiled bundle."""
    if rule_file is None:
        print("[!] No YARA file supplied; heuristics only.", file=sys.stderr)
        return []

    path = Path(rule_file).expanduser().resolve()
    if not path.is_file():
        raise FileNotFoundError(f"YARA rules file not found: {path}")

    compile_exc: Optional[BaseException] = None
    try:
        rs = yara.compile(filepath=str(path))
        print(f"[+] Compiled YARA source rules: {path}")
        return [rs]
    except Exception as exc:
        compile_exc = exc

    try:
        rs = yara.load(str(path))
        print(f"[+] Loaded compiled YARA bundle: {path}")
        return [rs]
    except Exception as load_exc:
        raise RuntimeError(
            f"Could not compile or load YARA rules from {path}. "
            f"compile error: {compile_exc}; load error: {load_exc}"
        ) from load_exc


# backward-compatible name
def compile_rules(rule_file: Path | None) -> List[yara.Rules]:
    return load_yara_rules(rule_file)


def byte_keyword_scan(bufs: Iterable[bytes], triggers: set[str]) -> None:
    for name in SUSPICIOUS_KEYS:
        needle = name.encode("ascii", "ignore")
        if needle and any(needle in b for b in bufs):
            triggers.add(f"bytes:{name}")


# ---------------------------------------------------------------------------
# Object analysis
# ---------------------------------------------------------------------------
def analyse_stream(
    st: pikepdf.Stream,
    rulesets: List[yara.Rules],
    whole_thr: float,
    win_size: int,
    win_step: int,
    win_thr: float,
    max_hits: int,
) -> Tuple[bool, Dict[str, Any], bytes, bytes]:
    meta: Dict[str, Any] = {"stream": True}
    try:
        raw = st.get_raw_stream_data()
    except Exception as exc:
        return False, {"stream": True, "error": f"raw_stream:{exc}"}, b"", b""

    try:
        dec = st.read_bytes()
    except Exception:
        dec = b""

    ent_raw = shannon_entropy(raw)
    ent_dec = shannon_entropy(dec)
    meta.update(
        raw_size=len(raw),
        dec_size=len(dec),
        ent_raw=round(ent_raw, 2),
        ent_dec=round(ent_dec, 2),
    )

    triggers: set[str] = set()

    if ent_raw > whole_thr or ent_dec > whole_thr:
        triggers.add("high_entropy")

    if win_size > 0:
        hits_raw = window_entropy(raw, win_size, win_step, win_thr)
        hits_dec = window_entropy(dec, win_size, win_step, win_thr)
        if hits_raw or hits_dec:
            triggers.add("high_entropy_window")
            meta["win_hits_raw"] = hits_raw[:max_hits]
            meta["win_hits_dec"] = hits_dec[:max_hits]

    scan_buf = dec or raw
    for rs in rulesets:
        try:
            for m in rs.match(data=scan_buf):
                triggers.add(m.rule)
        except yara.Error as exc:
            meta.setdefault("errors", []).append(f"yara:{exc}")

    declared = st.get("/Length")
    if isinstance(declared, (int, float)):
        if declared < 0:
            triggers.add("negative_length")
        elif int(declared) != meta["dec_size"]:
            triggers.add("length_mismatch")

    byte_keyword_scan([raw, dec], triggers)
    meta["triggers"] = sorted(triggers)
    return bool(triggers), meta, raw, dec


def analyse_object(
    num: int,
    obj: pikepdf.Object,
    rulesets: List[yara.Rules],
    whole_thr: float,
    win_size: int,
    win_step: int,
    win_thr: float,
    max_hits: int,
) -> Tuple[bool, Dict[str, Any], bytes, bytes]:
    if isinstance(obj, pikepdf.Stream):
        return analyse_stream(obj, rulesets, whole_thr, win_size, win_step, win_thr, max_hits)

    raw = safe_obj_bytes(obj)
    dec = b""
    triggers: set[str] = set()
    byte_keyword_scan([raw], triggers)
    meta: Dict[str, Any] = {"stream": False, "size": len(raw), "triggers": sorted(triggers)}
    return bool(triggers), meta, raw, dec


# ---------------------------------------------------------------------------
# Export helpers
# ---------------------------------------------------------------------------
def export_slice(
    pdf_stem: str,
    obj_num: int,
    raw: bytes,
    dec: bytes,
    bin_dir: Path,
    hex_dir: Path,
    write_hex: bool = False,
) -> Tuple[str, Optional[str], Optional[str]]:
    base_name = f"{pdf_stem}_{obj_num}"
    bin_dir.mkdir(parents=True, exist_ok=True)

    bin_path = bin_dir / f"{base_name}.bin"
    bin_path.write_bytes(raw or dec)

    hex_name: Optional[str] = None
    dec_hex_name: Optional[str] = None
    if write_hex:
        hex_dir.mkdir(parents=True, exist_ok=True)
        hex_path = hex_dir / f"{base_name}.hex"
        hex_path.write_text(hexdump(raw) if raw else "(no raw bytes available)", "utf-8")
        hex_name = hex_path.name
        if dec:
            dec_hex_path = hex_dir / f"{base_name}.dec.hex"
            dec_hex_path.write_text(hexdump(dec), "utf-8")
            dec_hex_name = dec_hex_path.name

    return bin_path.name, hex_name, dec_hex_name


def dump_all_objects(
    pdf: pikepdf.Pdf,
    pdf_name: str,
    pdf_stem: str,
    bin_dir: Path,
    hex_dir: Path,
    global_meta: List[Dict[str, Any]],
    write_hex: bool = False,
) -> None:
    total = len(pdf.objects)
    print(f"[*] {pdf_name}: {total} objects (export-all mode)")

    for num, obj in tqdm(enumerate(pdf.objects), total=total, leave=False):
        if obj is None:
            continue

        if isinstance(obj, pikepdf.Stream):
            try:
                raw = obj.get_raw_stream_data()
            except Exception:
                raw = b""
            try:
                dec = obj.read_bytes()
            except Exception:
                dec = b""
        else:
            raw = safe_obj_bytes(obj)
            dec = b""

        if len(raw) == 0 and len(dec) == 0:
            continue

        bin_name, hex_name, dec_hex_name = export_slice(pdf_stem, num, raw, dec, bin_dir, hex_dir, write_hex)
        slice_base = bin_name.rsplit(".", 1)[0]
        global_meta.append({
            "pdf": pdf_name,
            "obj": slice_base,
            "obj_num": num,
            "stream": isinstance(obj, pikepdf.Stream),
            "size": len(raw or dec),
            "triggers": [],
            "files": {"bin": bin_name, "hex": hex_name, "dec_hex": dec_hex_name},
        })


# ---------------------------------------------------------------------------
# Processing
# ---------------------------------------------------------------------------
def open_pdf_lenient(path: Path) -> pikepdf.Pdf:
    return pikepdf.open(str(path), allow_overwriting_input=True)


def process_single_pdf(
    pdf_path: Path,
    rulesets: List[yara.Rules],
    export_all: bool,
    bin_dir: Path,
    hex_dir: Path,
    global_meta: List[Dict[str, Any]],
    whole_thr: float,
    win_size: int,
    win_step: int,
    win_thr: float,
    max_hits: int,
    write_hex: bool = False,
) -> None:
    pdf_stem = pdf_path.stem

    try:
        pdf = open_pdf_lenient(pdf_path)
    except PdfError as e:
        print(f"[!] {pdf_path.name}: cannot open ({e}); skipping.", file=sys.stderr)
        return

    with pdf:
        if export_all:
            dump_all_objects(pdf, pdf_path.name, pdf_stem, bin_dir, hex_dir, global_meta, write_hex=write_hex)
            print(f"[+] {pdf_path.name}: exported all non-empty objects")
            return

        total = len(pdf.objects)
        print(f"[*] {pdf_path.name}: {total} objects")
        flagged_count = 0

        for num, obj in tqdm(enumerate(pdf.objects), total=total, leave=False):
            if obj is None:
                continue

            flagged, meta, raw, dec = analyse_object(
                num, obj, rulesets, whole_thr, win_size, win_step, win_thr, max_hits
            )
            if not flagged:
                continue

            flagged_count += 1
            bin_name, hex_name, dec_hex_name = export_slice(pdf_stem, num, raw, dec, bin_dir, hex_dir, write_hex)
            slice_base = bin_name.rsplit(".", 1)[0]
            global_meta.append({
                "pdf": pdf_path.name,
                "obj": slice_base,
                "obj_num": num,
                **meta,
                "files": {"bin": bin_name, "hex": hex_name, "dec_hex": dec_hex_name},
            })

        print(f"[+] {pdf_path.name}: {flagged_count} flagged objects")


def gather_pdfs(path: Path | str, recursive: bool) -> List[Path]:
    root = Path(path).expanduser().resolve()
    if root.is_file():
        return [root] if is_visible(root) and looks_like_pdf(root) else []
    if root.is_dir():
        pattern = "**/*" if recursive else "*"
        files = (p for p in root.glob(pattern) if p.is_file())
        return sorted(p for p in files if is_visible(p) and looks_like_pdf(p))
    return []


def run_pdf_slicer(
    input_path: Path | str,
    out_root: Path | str,
    rules_path: Path | str | None = None,
    recursive: bool = RECURSIVE_DEFAULT,
    export_all: bool = EXPORT_ALL_DEFAULT,
    win_size: int = WINDOW_SIZE,
    win_step: int = WINDOW_STEP,
    win_thr: float = WINDOW_THR,
    whole_thr: float = WHOLE_ENTROPY_THR,
    write_hex: bool = False,
) -> List[Dict[str, Any]]:
    """Callable PDF slicer entrypoint for slice_wrapper.py."""
    input_path = Path(input_path).expanduser().resolve()
    out_root = Path(out_root).expanduser().resolve()
    rules_file = Path(rules_path).expanduser().resolve() if rules_path else None

    pdf_list = gather_pdfs(input_path, recursive)
    if not pdf_list:
        raise FileNotFoundError(f"No suitable visible PDF-like files found under: {input_path}")

    rulesets: List[yara.Rules] = []
    if not export_all:
        rulesets = load_yara_rules(rules_file)
    elif rules_file:
        print("[*] --export-all specified: ignoring YARA rules.", file=sys.stderr)

    out_root.mkdir(parents=True, exist_ok=True)
    bin_dir = out_root
    hex_dir = out_root
    global_meta: List[Dict[str, Any]] = []

    for pdf_path in pdf_list:
        process_single_pdf(
            pdf_path,
            rulesets,
            export_all,
            bin_dir,
            hex_dir,
            global_meta,
            whole_thr,
            win_size,
            win_step,
            win_thr,
            MAX_WIN_HITS_LOG,
            write_hex=write_hex,
        )

    meta_path = out_root / GLOBAL_JSON_NAME
    meta_path.write_text(json.dumps(global_meta, indent=2), "utf-8")
    print(f"\n[✓] Finished. Metadata in: {meta_path}")
    return global_meta


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def main() -> None:
    ap = argparse.ArgumentParser(description="Flat PDF slicer with optional heuristics/YARA/entropy")
    ap.add_argument("input", type=Path, help="PDF file or folder, files without extension are accepted")
    ap.add_argument("out_root", type=Path, help="Root output directory")
    ap.add_argument("--rules", "-r", type=Path, help="YARA source file or compiled bundle")
    ap.add_argument("--recursive", action="store_true", default=RECURSIVE_DEFAULT, help="Recurse into subfolders")
    ap.add_argument("--export-all", action="store_true", default=EXPORT_ALL_DEFAULT, help="Export all objects and skip heuristics/YARA/entropy")
    ap.add_argument("--win-size", type=int, default=WINDOW_SIZE, help="Sliding entropy window size in bytes")
    ap.add_argument("--win-step", type=int, default=WINDOW_STEP, help="Sliding entropy window step in bytes")
    ap.add_argument("--win-thr", type=float, default=WINDOW_THR, help="Sliding-window entropy threshold")
    ap.add_argument("--whole-thr", type=float, default=WHOLE_ENTROPY_THR, help="Whole-stream entropy threshold")
    ap.add_argument("--write-hex", action="store_true", help="Also write .hex and decoded .dec.hex outputs")
    args = ap.parse_args()

    run_pdf_slicer(
        input_path=args.input,
        out_root=args.out_root,
        rules_path=args.rules,
        recursive=args.recursive,
        export_all=args.export_all,
        win_size=args.win_size,
        win_step=args.win_step,
        win_thr=args.win_thr,
        whole_thr=args.whole_thr,
        write_hex=args.write_hex,
    )


if __name__ == "__main__":
    main()
