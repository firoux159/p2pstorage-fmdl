#!/usr/bin/env python3
"""
modernise_yara.py  –  Upgrade legacy YARA rule files and bundle them.

Usage
-----
python modernise_yara.py <rules_folder> <output_file>

    rules_folder : directory tree containing *.yar / *.yara
    output_file  : consolidated modern rule file (e.g., modern_rules.yar)

Fixes applied
-------------
1. meta lines:  md5: c5ba...  -->  md5 = "c5ba..."
2. anonymous strings in 'strings:' blocks get auto IDs:
       "cmd.exe" ascii
   ->  $s001 = "cmd.exe" ascii
3. string lines missing '=':
       $id /regex/i
   ->  $id = /regex/i

Requires
--------
pip install yara-python
"""
from __future__ import annotations

import argparse
import itertools
import re
import textwrap
from pathlib import Path
from typing import List

import yara

# ──────────────────────────────────────────────────────────────────────────────
# Regex patterns
# ──────────────────────────────────────────────────────────────────────────────

# 1) meta lines: colon/equals without quotes
META_QUOTE_RE = re.compile(
    r"""
    ^(?P<indent>\s*)
    (?P<key>\w+)
    \s*[:=]\s*
    (?P<val>[0-9a-fA-F]{32,64})   # hash / hex
    \s*$
    """,
    re.VERBOSE | re.MULTILINE,
)

# 2) anonymous strings inside strings: block
ANON_STRING_RE = re.compile(
    r"""
    ^(?P<indent>\s*)
    (?:
        "(?:[^"\\]|\\.)*" |            # quoted string
        /[^/\n]+/[^/\s]*  |            # regex with optional mods
        \{[^\}]+\}                     # hex
    )
    (?P<mods>\s+(?:nocase|wide|ascii|fullword|xor)+)?\s*$
    """,
    re.VERBOSE | re.MULTILINE | re.IGNORECASE,
)

# 3) $id <pattern>  (missing '=')
MISSING_EQUAL_RE = re.compile(
    r"""
    ^(?P<indent>\s*)
    (?P<ident>\$\w+)
    \s+
    (?P<pattern>
        "(?:[^"\\]|\\.)*" |
        /[^/\n]+/[^/\s]*  |
        \{[^\}]+\}
    )
    (?P<mods>\s+(?:nocase|wide|ascii|fullword|xor)+)?\s*$
    """,
    re.VERBOSE | re.MULTILINE | re.IGNORECASE,
)

# ──────────────────────────────────────────────────────────────────────────────
def modernise_rule(text: str) -> str:
    """
    Return YARA rule text upgraded to compile with libyara 4.x.
    """

    # ─ 1. fix meta quoting / colon ─
    text = META_QUOTE_RE.sub(
        lambda m: f'{m.group("indent")}{m.group("key")} = "{m.group("val")}"', text
    )

    # ─ 2. fix anonymous strings inside strings: block ─
    def fix_anonymous(block: str) -> str:
        lines = block.splitlines()
        out: List[str] = []
        counter = itertools.count(1)
        for ln in lines:
            if ANON_STRING_RE.match(ln):
                idx = next(counter)
                sid = f"$s{idx:03d}"
                # re-insert modifiers if present
                m = ANON_STRING_RE.match(ln)
                mods = m.group("mods") if m else ""
                out.append(f'{m.group("indent")}{sid} = {ln.strip()}{mods or ""}')
            else:
                out.append(ln)
        return "\n".join(out)

    # walk the file, apply fix inside strings blocks
    lines_out: List[str] = []
    buf: List[str] = []
    in_strings = False
    for line in text.splitlines():
        if re.match(r"^\s*strings\s*:", line, re.I):
            in_strings = True
            lines_out.append(line)
            continue
        if in_strings and re.match(r"^\s*(condition|meta|rule)\b", line, re.I):
            lines_out.append(fix_anonymous("\n".join(buf)))
            buf.clear()
            in_strings = False
        if in_strings:
            buf.append(line)
        else:
            lines_out.append(line)
    if buf:
        lines_out.append(fix_anonymous("\n".join(buf)))
        buf.clear()

    text = "\n".join(lines_out)

    # ─ 3. add '=' for patterns missing it ─
    def repl_missing(m: re.Match) -> str:
        mods = m.group("mods") or ""
        return f'{m.group("indent")}{m.group("ident")} = {m.group("pattern")}{mods}'

    text = MISSING_EQUAL_RE.sub(repl_missing, text)

    return text

# ──────────────────────────────────────────────────────────────────────────────
def consolidate(folder: Path, out_file: Path) -> None:
    good_chunks: List[str] = []
    bad: List[str] = []

    for yar in folder.rglob("*.yar*"):
        raw = yar.read_text(encoding="utf-8", errors="ignore")
        fixed = modernise_rule(raw)

        try:
            yara.compile(source=fixed)  # validate
            header = f"\n/* ===== {yar.relative_to(folder)} ===== */"
            good_chunks.append(header + "\n" + fixed)
            print(f"[OK ] {yar}")
        except yara.SyntaxError as e:
            print(f"[BAD] {yar}: {e}")
            bad.append(f"{yar}: {e}")

    out_file.write_text("\n".join(good_chunks), "utf-8")
    print("\nDone.")
    print(f"  Written rulesets : {len(good_chunks)}")
    print(f"  Skipped (errors) : {len(bad)}")
    if bad:
        print("\nFiles still with errors:")
        for b in bad:
            print("   ", b)

# ──────────────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    ap = argparse.ArgumentParser(
        description="Modernise legacy .yar files and bundle into one output file"
    )
    ap.add_argument("rules_folder", type=Path, help="Folder with .yar files")
    ap.add_argument("output_file", type=Path, help="Consolidated output file")
    args = ap.parse_args()

    if not args.rules_folder.is_dir():
        ap.error("rules_folder must be a directory")

    consolidate(args.rules_folder, args.output_file)
