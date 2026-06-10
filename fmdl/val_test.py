"""
Run validation/test inference for the multimodal malware classifier.

This script owns the expensive part: loading the best checkpoint, rebuilding the
validation/test datasets, running inference, and saving observation-level model
outputs to the run directory. The plotting script can then be rerun without
loading the model again.

Default outputs:
  <run-dir>/validation_test_pr_predictions.csv
  <run-dir>/validation_test_pr_predictions_summary.json
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np
import torch
from sklearn.metrics import average_precision_score
from torch.cuda.amp import autocast
from torch.utils.data import DataLoader
from tqdm import tqdm


SPLIT_ALIASES = {
    "val": "validation",
    "validation": "validation",
    "test": "test",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run checkpoint inference on validation/test and save observation-level outputs."
    )

    parser.add_argument(
        "--run-dir",
        type=Path,
        default=Path("runs/multimodal_pbs"),
        help="Training output directory containing best_model.pt and args.json.",
    )
    parser.add_argument(
        "--checkpoint",
        type=Path,
        default=None,
        help="Checkpoint path. Defaults to <run-dir>/best_model.pt.",
    )
    parser.add_argument(
        "--data-root",
        type=str,
        default=None,
        help="Dataset root. Defaults to the value saved in checkpoint args / args.json.",
    )
    parser.add_argument(
        "--manifest",
        type=str,
        default=None,
        help="Optional manifest.csv path. Defaults to checkpoint args, then <data-root>/manifest.csv.",
    )
    parser.add_argument(
        "--project-root",
        type=Path,
        default=Path("."),
        help="Project root containing dataset.py and model.py. Defaults to the current directory.",
    )
    parser.add_argument(
        "--splits",
        nargs="+",
        default=["val", "test"],
        choices=["val", "validation", "test"],
        help="Dataset splits to run. Defaults to val test.",
    )

    parser.add_argument("--batch-size", type=int, default=None)
    parser.add_argument("--num-workers", type=int, default=None)
    parser.add_argument(
        "--device",
        type=str,
        default=None,
        help="cuda, cpu, etc. Defaults to checkpoint arg, then cuda if available else cpu.",
    )
    parser.add_argument(
        "--amp",
        action="store_true",
        help="Use CUDA mixed precision for inference. Only active on CUDA.",
    )
    parser.add_argument(
        "--validate-files",
        action="store_true",
        help="Ask MalwareChunkDataset to validate paths while constructing datasets.",
    )
    parser.add_argument(
        "--no-fixed-only",
        action="store_true",
        help="Disable fixed_only=True for val/test. You almost certainly do not want this.",
    )
    parser.add_argument(
        "--predictions-out",
        type=Path,
        default=None,
        help="Observation-level output CSV. Defaults to <run-dir>/validation_test_pr_predictions.csv.",
    )
    parser.add_argument(
        "--summary-out",
        type=Path,
        default=None,
        help="Inference summary JSON. Defaults to <run-dir>/validation_test_pr_predictions_summary.json.",
    )

    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def torch_load_checkpoint(path: Path, device: torch.device) -> Any:
    try:
        return torch.load(path, map_location=device, weights_only=False)
    except TypeError:
        return torch.load(path, map_location=device)


def add_project_root_to_pythonpath(project_root: Path) -> None:
    root = str(project_root.resolve())
    if root not in sys.path:
        sys.path.insert(0, root)


def get_cfg_value(
    cli_value: Any,
    ckpt_args: dict[str, Any],
    json_args: dict[str, Any],
    key: str,
    default: Any,
) -> Any:
    if cli_value is not None:
        return cli_value
    if key in ckpt_args and ckpt_args[key] is not None:
        return ckpt_args[key]
    if key in json_args and json_args[key] is not None:
        return json_args[key]
    return default


def bool_cfg(ckpt_args: dict[str, Any], json_args: dict[str, Any], key: str, default: bool = False) -> bool:
    if key in ckpt_args:
        return bool(ckpt_args[key])
    if key in json_args:
        return bool(json_args[key])
    return default


def strip_module_prefix(state_dict: dict[str, torch.Tensor]) -> dict[str, torch.Tensor]:
    if not state_dict:
        return state_dict
    if all(k.startswith("module.") for k in state_dict.keys()):
        return {k[len("module."):]: v for k, v in state_dict.items()}
    return state_dict


def move_batch_to_device(batch: dict[str, Any], device: torch.device) -> dict[str, torch.Tensor]:
    return {
        "input_ids": batch["input_ids"].to(device, non_blocking=True),
        "attention_mask": batch["attention_mask"].to(device, non_blocking=True),
        "images": batch["image"].to(device, non_blocking=True),
        "labels": batch["label"].to(device, non_blocking=True),
    }


@torch.no_grad()
def collect_predictions(
    *,
    model: torch.nn.Module,
    loader: DataLoader,
    device: torch.device,
    amp: bool,
    split_name: str,
) -> tuple[np.ndarray, np.ndarray]:
    model.eval()
    y_true: list[int] = []
    y_score: list[float] = []

    for raw_batch in tqdm(loader, desc=f"predict {split_name}", leave=False):
        batch = move_batch_to_device(raw_batch, device)
        with autocast(enabled=amp):
            logits = model(
                input_ids=batch["input_ids"],
                attention_mask=batch["attention_mask"],
                images=batch["images"],
            )
        probs = torch.softmax(logits, dim=1)[:, 1]
        y_score.extend(probs.detach().cpu().tolist())
        y_true.extend(batch["labels"].detach().cpu().tolist())

    return np.asarray(y_true, dtype=np.int64), np.asarray(y_score, dtype=np.float64)


def split_summary(y_true: np.ndarray, y_score: np.ndarray) -> dict[str, Any]:
    unique_labels = np.unique(y_true)
    ap = None
    if len(unique_labels) >= 2:
        ap = float(average_precision_score(y_true, y_score))

    return {
        "n": int(len(y_true)),
        "positives": int(y_true.sum()),
        "negatives": int((y_true == 0).sum()),
        "positive_rate": float(y_true.mean()) if len(y_true) else None,
        "average_precision": ap,
        "min_malware_probability": float(np.min(y_score)) if len(y_score) else None,
        "max_malware_probability": float(np.max(y_score)) if len(y_score) else None,
        "mean_malware_probability": float(np.mean(y_score)) if len(y_score) else None,
    }


def save_predictions_csv(
    path: Path,
    outputs_by_split: dict[str, tuple[np.ndarray, np.ndarray]],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(
            f,
            fieldnames=["split", "index", "label", "malware_probability"],
        )
        writer.writeheader()
        for split_name, (y_true, y_score) in outputs_by_split.items():
            for i, (label, score) in enumerate(zip(y_true.tolist(), y_score.tolist())):
                writer.writerow(
                    {
                        "split": split_name,
                        "index": i,
                        "label": int(label),
                        "malware_probability": float(score),
                    }
                )


def save_summary_json(path: Path, summary: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(summary, indent=2, sort_keys=True), encoding="utf-8")


def main() -> None:
    args = parse_args()
    add_project_root_to_pythonpath(args.project_root)

    from dataset import ByteEncodingConfig, MalwareChunkDataset
    from model import MultimodalMalwareClassifier

    checkpoint_path = args.checkpoint or (args.run_dir / "best_model.pt")
    predictions_out = args.predictions_out or (args.run_dir / "validation_test_pr_predictions.csv")
    summary_out = args.summary_out or (args.run_dir / "validation_test_pr_predictions_summary.json")

    if not checkpoint_path.exists():
        raise FileNotFoundError(f"Checkpoint not found: {checkpoint_path}")

    json_args = load_json(args.run_dir / "args.json")

    device_name = get_cfg_value(
        args.device,
        {},
        json_args,
        "device",
        "cuda" if torch.cuda.is_available() else "cpu",
    )
    device = torch.device(device_name)
    amp = bool(args.amp and device.type == "cuda")

    ckpt = torch_load_checkpoint(checkpoint_path, device)
    ckpt_args = ckpt.get("args", {}) if isinstance(ckpt, dict) else {}
    if not isinstance(ckpt_args, dict):
        ckpt_args = vars(ckpt_args)

    data_root = get_cfg_value(args.data_root, ckpt_args, json_args, "data_root", "dataset")
    manifest = get_cfg_value(args.manifest, ckpt_args, json_args, "manifest", None)
    manifest_csv = Path(manifest) if manifest else Path(data_root) / "manifest.csv"

    batch_size = int(get_cfg_value(args.batch_size, ckpt_args, json_args, "batch_size", 16))
    num_workers = int(get_cfg_value(args.num_workers, ckpt_args, json_args, "num_workers", 4))

    max_byte_length = int(get_cfg_value(None, ckpt_args, json_args, "max_byte_length", 4096))
    image_width = int(get_cfg_value(None, ckpt_args, json_args, "image_width", 256))
    image_size = int(get_cfg_value(None, ckpt_args, json_args, "image_size", 224))
    byt5_model = str(get_cfg_value(None, ckpt_args, json_args, "byt5_model", "google/byt5-base"))
    fusion_dim = int(get_cfg_value(None, ckpt_args, json_args, "fusion_dim", 512))
    dropout = float(get_cfg_value(None, ckpt_args, json_args, "dropout", 0.25))

    freeze_byt5 = bool_cfg(ckpt_args, json_args, "freeze_byt5", False)
    freeze_resnet_backbone = bool_cfg(ckpt_args, json_args, "freeze_resnet_backbone", False)
    gradient_checkpointing = bool_cfg(ckpt_args, json_args, "gradient_checkpointing", False)

    fixed_only = not args.no_fixed_only
    byte_config = ByteEncodingConfig(max_length=max_byte_length)

    print(f"Loading checkpoint: {checkpoint_path}")
    print(f"Using data_root={data_root}")
    print(f"Using manifest={manifest_csv}")
    print(f"Using device={device}; amp={amp}")
    print(f"Saving predictions to: {predictions_out}")
    print(f"Saving summary to: {summary_out}")

    if isinstance(ckpt, dict) and "model_state_dict" in ckpt:
        state_dict = ckpt["model_state_dict"]
        epoch = ckpt.get("epoch")
    elif isinstance(ckpt, dict):
        state_dict = ckpt
        epoch = None
    else:
        raise TypeError("Unsupported checkpoint format. Expected a dict or a dict with model_state_dict.")

    model = MultimodalMalwareClassifier(
        byt5_model_name=byt5_model,
        fusion_dim=fusion_dim,
        dropout=dropout,
        freeze_byt5=freeze_byt5,
        freeze_resnet_backbone=freeze_resnet_backbone,
        gradient_checkpointing=gradient_checkpointing,
    ).to(device)
    model.load_state_dict(strip_module_prefix(state_dict), strict=True)

    outputs_by_split: dict[str, tuple[np.ndarray, np.ndarray]] = {}
    counts_by_split: dict[str, Any] = {}

    for raw_split in args.splits:
        dataset_split = "val" if raw_split == "validation" else raw_split
        output_split = SPLIT_ALIASES[raw_split]

        dataset = MalwareChunkDataset(
            data_root=data_root,
            manifest_csv=manifest_csv,
            split=dataset_split,
            image_size=image_size,
            image_width=image_width,
            byte_config=byte_config,
            fixed_only=fixed_only,
            validate_files=args.validate_files,
        )
        counts = dataset.counts()
        counts_by_split[output_split] = counts
        print(f"{output_split} counts: {counts}")

        loader = DataLoader(
            dataset,
            batch_size=batch_size,
            shuffle=False,
            num_workers=num_workers,
            pin_memory=device.type == "cuda",
        )

        y_true, y_score = collect_predictions(
            model=model,
            loader=loader,
            device=device,
            amp=amp,
            split_name=output_split,
        )
        outputs_by_split[output_split] = (y_true, y_score)

        stats = split_summary(y_true, y_score)
        ap_text = "n/a" if stats["average_precision"] is None else f"{stats['average_precision']:.6f}"
        print(
            f"{output_split}: n={stats['n']} positives={stats['positives']} "
            f"AP={ap_text}"
        )

    save_predictions_csv(predictions_out, outputs_by_split)

    summary = {
        "checkpoint": str(checkpoint_path),
        "epoch": epoch,
        "epoch_label": f"epoch {epoch}" if epoch is not None else "checkpoint",
        "run_dir": str(args.run_dir),
        "data_root": str(data_root),
        "manifest_csv": str(manifest_csv),
        "device": str(device),
        "amp": amp,
        "fixed_only": fixed_only,
        "batch_size": batch_size,
        "num_workers": num_workers,
        "predictions_csv": str(predictions_out),
        "counts": counts_by_split,
        "splits": {
            split_name: split_summary(y_true, y_score)
            for split_name, (y_true, y_score) in outputs_by_split.items()
        },
    }
    save_summary_json(summary_out, summary)

    print(f"Saved predictions: {predictions_out}")
    print(f"Saved summary: {summary_out}")


if __name__ == "__main__":
    main()
