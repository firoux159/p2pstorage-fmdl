from __future__ import annotations

import base64
import binascii
import gzip
import re
import zlib
from typing import Dict, List, Optional

ASCII_RE = re.compile(rb"[ -~]{20,}")  # printable ASCII runs, length >= 20


def is_printable(data: bytes, threshold: float = 0.85) -> bool:
    if not data:
        return False
    return (sum(32 <= b <= 126 for b in data) / len(data)) >= threshold


def decompress(data: bytes) -> Optional[bytes]:
    try:
        if data.startswith(b"\x1f\x8b"):
            return gzip.decompress(data)
        if data.startswith((b"\x78\x01", b"\x78\x9c", b"\x78\xda")):
            return zlib.decompress(data)
    except (OSError, zlib.error):
        return None
    return None


def url_decode(data: bytes) -> Optional[bytes]:
    if b"%" not in data:
        return None
    try:
        return binascii.unhexlify(data.replace(b"%", b""))
    except binascii.Error:
        return None


def try_decode(data: bytes) -> Dict[str, bytes]:
    # simple encodings only
    out: Dict[str, bytes] = {}

    if re.fullmatch(rb"[0-9A-Fa-f]{40,}", data):
        try:
            out["hex"] = binascii.unhexlify(data)
        except binascii.Error:
            pass

    if re.fullmatch(rb"[A-Za-z0-9+/]{40,}={0,2}", data):
        try:
            out["b64"] = base64.b64decode(data, validate=True)
        except (binascii.Error, ValueError):
            pass

    if data.startswith(b"<~") and data.endswith(b"~>"):
        try:
            out["a85"] = base64.a85decode(data[2:-2])
        except (binascii.Error, ValueError):
            pass

    decoded_url = url_decode(data)
    if decoded_url:
        out["url"] = decoded_url

    extra: Dict[str, bytes] = {}
    for label, decoded in out.items():
        decomp = decompress(decoded)
        if decomp:
            extra[f"{label}+z"] = decomp
        try:
            nested = base64.b64decode(decoded, validate=True)
            if nested:
                extra[f"{label}+b64"] = nested
        except (binascii.Error, ValueError):
            pass
    out.update(extra)

    # Single-byte XOR brute force: keep first printable candidate only.
    for key in range(1, 256):
        candidate = bytes(b ^ key for b in data)
        if is_printable(candidate):
            out[f"xor-{key:02x}"] = candidate
            break

    return out


def decoded_snips(blob: bytes, limit: int = 4, max_each: int = 1024) -> List[str]:
    snippets: List[str] = []
    for match in ASCII_RE.finditer(blob):
        for label, decoded in try_decode(match.group()).items():
            snippets.append(f"[{label} {len(decoded)}B]\n{decoded[:max_each].hex(' ', 1)}")
            if len(snippets) >= limit:
                return snippets
    return snippets


def hexdump(data: bytes, width: int = 16, max_lines: int | None = None) -> str:
    lines: List[str] = []
    stop = len(data)
    if max_lines is not None:
        stop = min(stop, max_lines * width)
    for offset in range(0, stop, width):
        chunk = data[offset : offset + width]
        hex_bytes = " ".join(f"{b:02x}" for b in chunk)
        ascii_rep = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        lines.append(f"{offset:08x}  {hex_bytes:<{width * 3}}  {ascii_rep}")
    return "\n".join(lines)
