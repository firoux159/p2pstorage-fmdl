#!/usr/bin/env python3

"""
run this script from the root of the project
e.g., python utils/build_obj_manifest.py
"""

import argparse
import csv
from pathlib import Path

def guess_file_id(fname: str) -> str:
    """Derive file_id from the slice filename (prefix before first underscore)."""
    return fname.split("_", 1)[0]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="data/pdf", help="Root folder containing benign/ and mware/")
    ap.add_argument("--out_csv", default="data/pdf/obj_manifest.csv", help="Output CSV path")
    ap.add_argument("--prefix", default="data/pdf", help="Prefix to add to the path column")
    ap.add_argument("--exts", nargs="+", default=[".bin", ".dat", ".slice"], help="Allowed file extensions")
    args = ap.parse_args()

    root = Path(args.root)
    sub_to_label = {"benign": 0, "mware": 1}

    rows = []
    for sub, label in sub_to_label.items():
        folder = root / sub
        if not folder.exists():
            print(f"[WARN] Folder not found: {folder}")
            continue
        for p in folder.rglob("*"):
            if p.is_file() and p.suffix.lower() in args.exts:
                rel = (Path(args.prefix) / sub / p.name).as_posix()
                file_id = guess_file_id(p.name)
                rows.append({"path": rel, "label": label, "file_id": file_id})

    # Write CSV
    with open(args.out_csv, "w", newline="") as fp:
        writer = csv.DictWriter(fp, fieldnames=["path", "label", "file_id"])
        writer.writeheader()
        writer.writerows(rows)

    print(f"Wrote {len(rows)} rows to {args.out_csv}")

if __name__ == "__main__":
    main()
