"""
Plot Validation vs Test precision-recall curves for the multimodal malware classifier.

Reload the trained checkpoint, reruns inference on the validation and test splits, computes PR curves from raw probabilities, and plots
both curves with a static prevalence baseline.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path
from typing import Any

import matplotlib.pyplot as plt
import numpy as np
import torch
from sklearn.metrics import average_precision_score, precision_recall_curve
from torch.cuda.amp import autocast
from torch.utils.data import DataLoader
from tqdm import tqdm


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Plot validation vs test PR curves by rerunning checkpoint inference"
    )

    parser.add_argument(
        "--run-dir",
        type=Path,
        default=Path("runs/multimodal_pbs"),
        help="Training output directory containing best_model.pt ands args.json",
    )
    parser.add_argument(
        "--checkpoint",
        type=Path,
        default=None,
        help="Checkpoint path. Defaults to <run-dir>/best_model.pt",
    )
    parser.add_argument(
        "--data-root",
        type=str,
        default=None,
        help="Dataset root. Defaults to the value saved in checkpoint args / args.json",
    )
    parser.add_argument(
        "--manifest",
        type=str,
        default=None,
        help="Optional manifest.csv path. Defaults to checkpoint args, then <data-root>/manifest.csv",
    )
    parser.add_argument(
        "--project-root",
        type=Path,
        default=Path("."),
        help="Project root containing dataset.py and model.py. Defaults to the current directory",
    )

    parser.add_argument(
        "--prevalence",
        type=float,
        required=True,
        help="Static prevalence baseline. Use x.xx for x.xx%% or 0.0xxx as a fraction",
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
        "--out",
        type=Path,
        default=None,
        help="Output PNG path. Defaults to <run-dir>/validation_vs_test_pr_curves.png.",
    )
    parser.add_argument(
        "--title",
        type=str,
        default="FMDL—PDF: Validation vs Test PR Curves",
    )
    parser.add_argument(
        "--save-csv",
        action="store_true",
        help="Also save raw predictions and plotted PR curve points into run-dir.",
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


def get_cfg_value(cli_value: Any, ckpt_args: dict[str, Any], json_args: dict[str, Any], key: str, default: Any) -> Any:
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

    y_true_arr = np.asarray(y_true, dtype=np.int64)
    y_score_arr = np.asarray(y_score, dtype=np.float64)

    if len(np.unique(y_true_arr)) < 2:
        raise ValueError(
            f"Split {split_name!r} contains only one class. A PR curve is not meaningful."
        )

    return y_true_arr, y_score_arr


def make_curve(y_true: np.ndarray, y_score: np.ndarray) -> dict[str, Any]:
    precision, recall, thresholds = precision_recall_curve(y_true, y_score)

    # sklearn returns recall from high to low. Need to reverse it so the plot moves left-to-right.
    precision_plot = precision[::-1]
    recall_plot = recall[::-1]

    return {
        "precision": precision_plot,
        "recall": recall_plot,
        "thresholds": thresholds,
        "ap": float(average_precision_score(y_true, y_score)),
        "n": int(len(y_true)),
        "positives": int(y_true.sum()),
        "negatives": int((y_true == 0).sum()),
    }


def save_predictions_csv(path: Path, split_name: str, y_true: np.ndarray, y_score: np.ndarray, append: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = "a" if append else "w"
    with path.open(mode, newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["split", "index", "label", "malware_probability"])
        if not append:
            writer.writeheader()
        for i, (label, score) in enumerate(zip(y_true.tolist(), y_score.tolist())):
            writer.writerow(
                {
                    "split": split_name,
                    "index": i,
                    "label": int(label),
                    "malware_probability": float(score),
                }
            )


def save_curve_csv(path: Path, split_name: str, curve: dict[str, Any], append: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = "a" if append else "w"
    with path.open(mode, newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["split", "point", "recall", "precision"])
        if not append:
            writer.writeheader()
        for i, (r, p) in enumerate(zip(curve["recall"].tolist(), curve["precision"].tolist())):
            writer.writerow({"split": split_name, "point": i, "recall": float(r), "precision": float(p)})


def plot_curves(
    *,
    val_curve: dict[str, Any],
    test_curve: dict[str, Any],
    prevalence: float,
    epoch_label: str,
    title: str,
    out_path: Path,
) -> None:
    if prevalence > 1.0:
        prevalence = prevalence / 100.0
    if not (0.0 <= prevalence <= 1.0):
        raise ValueError("prevalence must be either a fraction in [0, 1] or a percent in [0, 100].")

    fig, ax = plt.subplots(figsize=(12, 8), dpi=128)

    # Keep styling close to the sample image.
    ax.plot(
        val_curve["recall"],
        val_curve["precision"],
        marker="o",
        linewidth=2.5,
        markersize=7,
        label=f"Validation ({epoch_label})  PR-AUC≈{val_curve['ap']:.3f}",
    )
    ax.plot(
        test_curve["recall"],
        test_curve["precision"],
        linestyle="--",
        marker="x",
        linewidth=2.5,
        markersize=7,
        label=f"Test ({epoch_label})          PR-AUC≈{test_curve['ap']:.3f}",
    )
    ax.axhline(
        prevalence,
        linestyle=":",
        linewidth=2.5,
        label=f"Prevalence={prevalence * 100:.2f}%",
    )

    ax.set_title(title, fontsize=22, pad=12)
    ax.set_xlabel("Recall", fontsize=18)
    ax.set_ylabel("Precision", fontsize=18)
    ax.set_xlim(0.0, 1.0)
    ax.set_ylim(0.0, 1.0)
    ax.grid(True, linestyle="--", linewidth=1.2, alpha=0.6)
    ax.legend(loc="lower left", fontsize=16, frameon=True)
    ax.tick_params(axis="both", labelsize=16)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.tight_layout()
    fig.savefig(out_path, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    args = parse_args()
    add_project_root_to_pythonpath(args.project_root)

    from dataset import ByteEncodingConfig, MalwareChunkDataset
    from model import MultimodalMalwareClassifier

    checkpoint_path = args.checkpoint or (args.run_dir / "best_model.pt")
    out_path = args.out or (args.run_dir / "validation_vs_test_pr_curves.png")

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

    data_root = get_cfg_value(args.data_root, ckpt_args, json_args, "data_root", "dataaset")
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

    val_dataset = MalwareChunkDataset(
        data_root=data_root,
        manifest_csv=manifest_csv,
        split="val",
        image_size=image_size,
        image_width=image_width,
        byte_config=byte_config,
        fixed_only=fixed_only,
        validate_files=args.validate_files,
    )
    test_dataset = MalwareChunkDataset(
        data_root=data_root,
        manifest_csv=manifest_csv,
        split="test",
        image_size=image_size,
        image_width=image_width,
        byte_config=byte_config,
        fixed_only=fixed_only,
        validate_files=args.validate_files,
    )

    print("val counts:", val_dataset.counts())
    print("test counts:", test_dataset.counts())

    val_loader = DataLoader(
        val_dataset,
        batch_size=batch_size,
        shuffle=False,
        num_workers=num_workers,
        pin_memory=device.type == "cuda",
    )
    test_loader = DataLoader(
        test_dataset,
        batch_size=batch_size,
        shuffle=False,
        num_workers=num_workers,
        pin_memory=device.type == "cuda",
    )

    model = MultimodalMalwareClassifier(
        byt5_model_name=byt5_model,
        fusion_dim=fusion_dim,
        dropout=dropout,
        freeze_byt5=freeze_byt5,
        freeze_resnet_backbone=freeze_resnet_backbone,
        gradient_checkpointing=gradient_checkpointing,
    ).to(device)

    if isinstance(ckpt, dict) and "model_state_dict" in ckpt:
        state_dict = ckpt["model_state_dict"]
        epoch = ckpt.get("epoch")
    elif isinstance(ckpt, dict):
        state_dict = ckpt
        epoch = None
    else:
        raise TypeError("Unsupported checkpoint format. Expected a dict or a dict with model_state_dict.")

    model.load_state_dict(strip_module_prefix(state_dict), strict=True)
    epoch_label = f"epoch {epoch}" if epoch is not None else "checkpoint"

    val_y, val_prob = collect_predictions(
        model=model,
        loader=val_loader,
        device=device,
        amp=amp,
        split_name="validation",
    )
    test_y, test_prob = collect_predictions(
        model=model,
        loader=test_loader,
        device=device,
        amp=amp,
        split_name="test",
    )

    val_curve = make_curve(val_y, val_prob)
    test_curve = make_curve(test_y, test_prob)

    print(
        f"Validation: n={val_curve['n']} positives={val_curve['positives']} "
        f"PR-AUC/AP={val_curve['ap']:.6f}"
    )
    print(
        f"Test:       n={test_curve['n']} positives={test_curve['positives']} "
        f"PR-AUC/AP={test_curve['ap']:.6f}"
    )

    plot_curves(
        val_curve=val_curve,
        test_curve=test_curve,
        prevalence=args.prevalence,
        epoch_label=epoch_label,
        title=args.title,
        out_path=out_path,
    )
    print(f"Saved plot: {out_path}")

    if args.save_csv:
        pred_path = args.run_dir / "validation_test_pr_predictions.csv"
        curve_path = args.run_dir / "validation_test_pr_curve_points.csv"
        save_predictions_csv(pred_path, "validation", val_y, val_prob, append=False)
        save_predictions_csv(pred_path, "test", test_y, test_prob, append=True)
        save_curve_csv(curve_path, "validation", val_curve, append=False)
        save_curve_csv(curve_path, "test", test_curve, append=True)
        print(f"Saved predictions: {pred_path}")
        print(f"Saved curve points: {curve_path}")


if __name__ == "__main__":
    main()
