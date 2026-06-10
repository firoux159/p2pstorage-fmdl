#!/usr/bin/env python3
"""
run this script from the root of the project
e.g., python utils/split_dataset.py
"""

import argparse
from pathlib import Path
from typing import Tuple
import pandas as pd


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
        CSV with columns: path,label,file_id
    train_ratio : float
        Fraction of unique file_ids for the train split.
    val_ratio : float
        Fraction for validation. Test gets the remainder.
    seed : int
        RNG seed for reproducibility.
    out_dir : str
        Directory to save the split CSVs.
    prefix : str
        Prefix for output filenames (e.g., 'obj' -> obj_train.csv)

    Returns
    -------
    (train_csv, val_csv, test_csv) : Tuple[str, str, str]
        Paths to the written CSV files.
    """
    df = pd.read_csv(manifest_csv)
    required = {"path", "label", "file_id"}
    if not required.issubset(df.columns):
        raise ValueError(f"CSV must contain columns {required}")

    # Shuffle file_ids
    file_ids = df["file_id"].drop_duplicates().sample(frac=1.0, random_state=seed).tolist()
    n = len(file_ids)
    n_train = int(train_ratio * n)
    n_val = int(val_ratio * n)

    train_ids = set(file_ids[:n_train])
    val_ids   = set(file_ids[n_train:n_train + n_val])
    test_ids  = set(file_ids[n_train + n_val:])

    out_path = Path(out_dir)
    out_path.mkdir(parents=True, exist_ok=True)

    train_csv = out_path / f"{prefix}_train.csv"
    val_csv   = out_path / f"{prefix}_val.csv"
    test_csv  = out_path / f"{prefix}_test.csv"

    df[df.file_id.isin(train_ids)].to_csv(train_csv, index=False)
    df[df.file_id.isin(val_ids)].to_csv(val_csv, index=False)
    df[df.file_id.isin(test_ids)].to_csv(test_csv, index=False)

    return str(train_csv), str(val_csv), str(test_csv)


def _parse_args():
    p = argparse.ArgumentParser(description="Split manifest by file_id (no slice leakage).")
    p.add_argument("--manifest", default="../data/pdf/obj_manifest.csv", help="Path to obj_manifest.csv")
    p.add_argument("--out_dir", default="../data/pdf", help="Directory to save split CSVs")
    p.add_argument("--train_ratio", type=float, default=0.70, help="Train fraction (files)")
    p.add_argument("--val_ratio", type=float, default=0.15, help="Val fraction (files)")
    p.add_argument("--seed", type=int, default=42, help="Random seed")
    p.add_argument("--prefix", default="obj", help="Prefix for output CSV names")
    return p.parse_args()


def main():
    args = _parse_args()
    tr, va, te = split_by_file(
        manifest_csv=args.manifest,
        train_ratio=args.train_ratio,
        val_ratio=args.val_ratio,
        seed=args.seed,
        out_dir=args.out_dir,
        prefix=args.prefix
    )
    print(f"Train: {tr}\nVal:   {va}\nTest:  {te}")


if __name__ == "__main__":
    main()
