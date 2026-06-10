"""
Dataset and byte-tokenization logic for multimodal malware training.
"""

from __future__ import annotations

import random
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, Optional

import pandas as pd
import torch
from torch.utils.data import Dataset

from binary_image import binary_to_pil, build_image_transform


REQUIRED_COLUMNS = {"sample_id", "file_path", "label", "label_id", "split"}
VALID_LABELS = {"benign": 0, "malware": 1}
VALID_SPLITS = {"train", "val", "test"}
VALID_CHUNK_TYPES = {"obj", "fixed"}


@dataclass(frozen=True)
class ByteEncodingConfig:
    max_length: int = 4096
    append_eos: bool = True
    pad_id: int = 0
    eos_id: int = 1
    byte_offset: int = 3
    random_crop_train: bool = True


def infer_chunk_type(file_path: str | Path) -> str:
    """Infer `obj` or `fixed` from a path """
    parts = {part.lower() for part in Path(file_path).parts}
    if "obj" in parts:
        return "obj"
    if "fixed" in parts:
        return "fixed"
    raise ValueError(f"Cannot infer chunk type from file_path={file_path!s}; expected path to contain obj or fixed.")


def resolve_file_path(data_root: str | Path, manifest_path: str | Path, raw_path: str | Path) -> Path:
    raw = Path(str(raw_path))
    if raw.is_absolute():
        return raw

    data_root_path = Path(data_root)
    candidate = data_root_path / raw
    if candidate.exists():
        return candidate

    manifest_parent = Path(manifest_path).parent
    candidate = manifest_parent / raw
    if candidate.exists():
        return candidate

    # Return the preferred path for a clear FileNotFoundError later.
    return data_root_path / raw


def encode_raw_bytes_for_byt5(
    file_path: str | Path,
    *,
    config: ByteEncodingConfig,
    train: bool = False,
) -> tuple[torch.Tensor, torch.Tensor]:
    """
    Convert raw bytes to ByT5-compatible token IDs.

    ByT5 reserves 0=<pad>, 1=</s>, 2=<unk>; byte values map to byte+3.
    This preserves arbitrary binary bytes without decoding them as text.
    """
    data = Path(file_path).read_bytes()
    if not data:
        raise ValueError(f"Empty binary chunk: {file_path}")

    usable_len = config.max_length - (1 if config.append_eos else 0)
    if usable_len <= 0:
        raise ValueError("max_length must be > 1 when append_eos=True")

    if len(data) > usable_len:
        if train and config.random_crop_train:
            start = random.randint(0, len(data) - usable_len)
            data = data[start : start + usable_len]
        else:
            data = data[:usable_len]

    ids = torch.empty(config.max_length, dtype=torch.long)
    ids.fill_(config.pad_id)

    raw = torch.tensor(list(data), dtype=torch.long) + config.byte_offset
    n = min(raw.numel(), usable_len)
    ids[:n] = raw[:n]

    length = n
    if config.append_eos and length < config.max_length:
        ids[length] = config.eos_id
        length += 1

    attention_mask = torch.zeros(config.max_length, dtype=torch.long)
    attention_mask[:length] = 1
    return ids, attention_mask


class MalwareChunkDataset(Dataset):
    """
    Multimodal dataset returning byte tokens + rendered image + label

    For train split, must use obj and fixed chunks
    For val/test split, only use fixed chunks
    """

    def __init__(
        self,
        *,
        data_root: str | Path,
        manifest_csv: str | Path | None = None,
        split: str,
        image_size: int = 224,
        image_width: int = 256,
        byte_config: Optional[ByteEncodingConfig] = None,
        fixed_only: Optional[bool] = None,
        validate_files: bool = False,
    ) -> None:
        self.data_root = Path(data_root)
        self.manifest_csv = Path(manifest_csv) if manifest_csv else self.data_root / "manifest.csv"
        self.split = split.lower()
        self.image_width = int(image_width)
        self.byte_config = byte_config or ByteEncodingConfig()

        if self.split not in VALID_SPLITS:
            raise ValueError(f"Invalid split={split!r}; expected one of {sorted(VALID_SPLITS)}")
        if not self.manifest_csv.exists():
            raise FileNotFoundError(f"Manifest not found: {self.manifest_csv}")

        df = pd.read_csv(self.manifest_csv)
        missing = REQUIRED_COLUMNS - set(df.columns)
        if missing:
            raise ValueError(f"manifest.csv is missing required columns: {sorted(missing)}")

        df["split"] = df["split"].astype(str).str.lower()
        df["label"] = df["label"].astype(str).str.lower()
        df = df[df["split"] == self.split].copy()

        if df.empty:
            raise ValueError(f"No rows found for split={self.split!r} in {self.manifest_csv}")

        df["chunk_type"] = df["file_path"].apply(infer_chunk_type)
        if fixed_only is None:
            fixed_only = self.split in {"val", "test"}
        if fixed_only:
            df = df[df["chunk_type"] == "fixed"].copy()

        # Hard sanity checks. Silent label mistakes poison the whole experiment.
        bad_labels = df[~df["label"].isin(VALID_LABELS.keys())]
        if not bad_labels.empty:
            raise ValueError(f"Invalid label values found: {sorted(bad_labels['label'].unique())}")

        mismatched = df[df.apply(lambda r: int(r["label_id"]) != VALID_LABELS[str(r["label"])], axis=1)]
        if not mismatched.empty:
            raise ValueError("manifest.csv has label/label_id mismatches. Fix it before training.")

        df["abs_path"] = df["file_path"].apply(lambda p: resolve_file_path(self.data_root, self.manifest_csv, p))
        if validate_files:
            missing_paths = [str(p) for p in df["abs_path"] if not Path(p).exists()]
            if missing_paths:
                preview = "\n".join(missing_paths[:10])
                raise FileNotFoundError(f"Missing chunk files. First missing paths:\n{preview}")

        if df.empty:
            raise ValueError(f"No usable rows left for split={self.split!r}; check fixed_only and paths.")

        self.df = df.reset_index(drop=True)
        self.transform_fixed_train = build_image_transform(image_size=image_size, train=True, augment_fixed=True)
        self.transform_standard = build_image_transform(image_size=image_size, train=False, augment_fixed=False)

    def __len__(self) -> int:
        return len(self.df)

    @property
    def labels(self) -> list[int]:
        return [int(x) for x in self.df["label_id"].tolist()]

    @property
    def chunk_types(self) -> list[str]:
        return [str(x) for x in self.df["chunk_type"].tolist()]

    def counts(self) -> Dict[str, Any]:
        return {
            "split": self.split,
            "total": len(self.df),
            "by_label": self.df["label"].value_counts().to_dict(),
            "by_chunk_type": self.df["chunk_type"].value_counts().to_dict(),
            "by_chunk_type_and_label": self.df.groupby(["chunk_type", "label"]).size().to_dict(),
        }

    def __getitem__(self, idx: int) -> Dict[str, Any]:
        row = self.df.iloc[idx]
        file_path = Path(row["abs_path"])
        chunk_type = str(row["chunk_type"])
        label = int(row["label_id"])

        input_ids, attention_mask = encode_raw_bytes_for_byt5(
            file_path,
            config=self.byte_config,
            train=self.split == "train",
        )

        image = binary_to_pil(file_path, width=self.image_width, pad=True)
        if self.split == "train" and chunk_type == "fixed":
            image_tensor = self.transform_fixed_train(image)
        else:
            image_tensor = self.transform_standard(image)

        return {
            "input_ids": input_ids,
            "attention_mask": attention_mask,
            "image": image_tensor,
            "label": torch.tensor(label, dtype=torch.long),
            "chunk_type": chunk_type,
            "sample_id": str(row["sample_id"]),
            "file_path": str(row["file_path"]),
        }


def summarize_manifest(data_root: str | Path, manifest_csv: str | Path | None = None) -> dict[str, Any]:
    manifest = Path(manifest_csv) if manifest_csv else Path(data_root) / "manifest.csv"
    df = pd.read_csv(manifest)
    df["chunk_type"] = df["file_path"].apply(infer_chunk_type)
    return {
        "total": len(df),
        "split_counts": df["split"].value_counts().to_dict(),
        "label_counts": df["label"].value_counts().to_dict(),
        "chunk_type_counts": df["chunk_type"].value_counts().to_dict(),
        "split_chunk_label_counts": df.groupby(["split", "chunk_type", "label"]).size().to_dict(),
    }
