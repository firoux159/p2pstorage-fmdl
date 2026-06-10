#!/usr/bin/env python3
"""
misc.py - Miscelaneous preparation helpers
--------------------------------------

Sub-commands
------------
label  : Generate label.csv from benign/ and mware/ sub-folders
rename : Rename all files in a folder with mw_<random10>.* pattern

Examples
--------
# 1) Build label.csv in the dataset root
python misc.py label /path/to/dataset_root

# 2) Build label.csv in a specific directory
python misc.py label /path/to/dataset_root --dest /tmp/output_dir

# 3) Preview file renaming
python misc.py rename /path/to/folder --dry-run

# 4) Perform the renaming
python misc.py rename /path/to/folder
"""

import argparse
import csv
import os
import pandas as pd
import secrets
import string

from pathlib import Path
from typing import Tuple

# ────────────────────────────────────────────────────────────────
#  Label‑creation helpers
# ────────────────────────────────────────────────────────────────
LABEL_DIRS = {
    "benign": "benign",
    "malware":  "malware",   # folder name → label
}

def build_label_file(root: Path, dest_dir: Path) -> None:
    """Create label.csv from benign/ and mware/ under *root*."""
    rows: list[list[str]] = []

    for subdir, label in LABEL_DIRS.items():
        d = root / subdir
        if not d.is_dir():
            print(f"[WARN] directory missing: {d}")
            continue

        for file in d.iterdir():          # use d.rglob('*') to recurse
            if file.is_file():
                rows.append([file.name, label])

    rows.sort(key=lambda r: r[0])         # deterministic order

    dest_dir.mkdir(parents=True, exist_ok=True)
    out_csv = dest_dir / "label.csv"

    with out_csv.open("w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        #writer.writerow(["filename", "label"])
        writer.writerows(rows)

    print(f"[OK] wrote {len(rows)} rows → {out_csv.resolve()}")

# ────────────────────────────────────────────────────────────────
#  File‑renaming helpers
# ────────────────────────────────────────────────────────────────
ALPHANUM   = string.ascii_letters + string.digits
PREFIX     = "mw"
RAND_LEN   = 10

def _unique_random(existing: set[str]) -> str:
    """Return a 10-char alphanumeric string not present in *existing*."""
    while True:
        s = ''.join(secrets.choice(ALPHANUM) for _ in range(RAND_LEN))
        if s not in existing:
            return s

def rename_files(folder: Path, dry_run: bool = False) -> None:
    """Rename every regular file in *folder*."""
    if not folder.is_dir():
        raise ValueError(f"Not a directory: {folder}")

    used: set[str] = set()

    for path in folder.iterdir():
        if not path.is_file():
            continue

        ext      = path.suffix              # keeps the original extension
        new_stem = _unique_random(used)
        used.add(new_stem)

        new_name = f"{PREFIX}{new_stem}{ext}"
        new_path = path.with_name(new_name)

        if dry_run:
            print(f"[DRY-RUN] {path.name} → {new_name}")
        else:
            os.rename(path, new_path)
            print(f"[RENAMED] {path.name} → {new_name}")

# ────────────────────────────────────────────────────────────────
#  Command‑line interface
# ────────────────────────────────────────────────────────────────
def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Dataset utility toolkit (label generator + renamer)")
    sub = p.add_subparsers(dest="cmd", required=True)

    # label sub‑command
    p_label = sub.add_parser("label", help="generate label.csv")
    p_label.add_argument(
        "dataset_root", type=Path,
        help="Folder containing benign/ and mware/ directories")
    p_label.add_argument(
        "--dest", type=Path, default=None,
        help="Custom destination directory for label.csv "
             "(default: dataset_root)")

    # rename sub‑command
    p_rename = sub.add_parser("rename", help="rename files randomly")
    p_rename.add_argument(
        "dir", type=Path,
        help="Directory whose files will be renamed")
    p_rename.add_argument(
        "--dry-run", action="store_true",
        help="Show planned changes without renaming")

    return p.parse_args()

def main() -> None:
    args = _parse_args()

    if args.cmd == "label":
        dest = args.dest or args.dataset_root
        build_label_file(args.dataset_root, dest)

    elif args.cmd == "rename":
        rename_files(args.dir, dry_run=args.dry_run)

    else:  # should never happen (argparse enforces choices)
        raise RuntimeError("Unknown command: %s" % args.cmd)

if __name__ == "__main__":
    main()


def split_by_file(
    manifest_csv: str,
    train_ratio: float = 0.70,
    val_ratio: float = 0.15,
    seed: int = 42,
    out_dir: str = ".",
    prefix: str = "obj"
) -> Tuple[str, str, str]:
    """
    Split an object-slice manifest *by file_id*, not by individual slices.

    Parameters
    ----------
    manifest_csv : str
        Path to CSV with at least columns: path,label,file_id
    train_ratio : float
        Fraction of unique file_ids to place in the train split.
    val_ratio : float
        Fraction for validation. Test gets the remainder.
    seed : int
        RNG seed for reproducibility.
    out_dir : str
        Directory to save the split CSVs.
    prefix : str
        Filename prefix for the output CSVs (e.g., 'obj' -> obj_train.csv)

    Returns
    -------
    (train_csv, val_csv, test_csv) : Tuple[str, str, str]
        Paths to the written CSV files.
    """
    df = pd.read_csv(manifest_csv)
    assert {"path", "label", "file_id"}.issubset(df.columns), "CSV must have path,label,file_id"

    rng = pd.Series(df["file_id"].unique()).sample(frac=1.0, random_state=seed).tolist()
    n = len(rng)
    n_train = int(train_ratio * n)
    n_val   = int(val_ratio   * n)

    train_ids = set(rng[:n_train])
    val_ids   = set(rng[n_train:n_train + n_val])
    test_ids  = set(rng[n_train + n_val:])

    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    train_csv = out_dir / f"{prefix}_train.csv"
    val_csv   = out_dir / f"{prefix}_val.csv"
    test_csv  = out_dir / f"{prefix}_test.csv"

    df[df.file_id.isin(train_ids)].to_csv(train_csv, index=False)
    df[df.file_id.isin(val_ids)].to_csv(val_csv, index=False)
    df[df.file_id.isin(test_ids)].to_csv(test_csv, index=False)

    return str(train_csv), str(val_csv), str(test_csv)


