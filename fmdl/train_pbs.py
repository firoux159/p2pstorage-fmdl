"""
Train the multimodal malware classifier with Progressive Balance Shifting (PBS)
Also keep track PBS progress to observe  precision, recall, F1, ROC-AUC and PR-AUC change across the PBS phases
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import random
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import numpy as np
import torch
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from torch import nn
from torch.cuda.amp import GradScaler, autocast
from torch.optim import AdamW
from torch.utils.data import DataLoader
from tqdm import tqdm

from dataset import ByteEncodingConfig, MalwareChunkDataset, summarize_manifest
from models.MultiModalClassifier import MultiModalClassifier
from sampler import PBSBatchSampler, pbs_phase_bounds, pbs_ratios

try:
    from torch.utils.tensorboard import SummaryWriter
except Exception: 
    SummaryWriter = None


TRACKING_COLUMNS = [
    "epoch",
    "pbs_phase",
    "phase_index",
    "phase_epoch",
    "phase_start_epoch",
    "phase_end_epoch",
    "obj_ratio",
    "fixed_ratio",
    "sampled_total",
    "sampled_obj",
    "sampled_fixed",
    "sampled_benign",
    "sampled_malware",
    "train_loss",
    "train_accuracy",
    "train_precision",
    "train_recall",
    "train_f1",
    "train_roc_auc",
    "train_pr_auc",
    "train_tp",
    "train_fp",
    "train_tn",
    "train_fn",
    "val_loss",
    "val_accuracy",
    "val_precision",
    "val_recall",
    "val_f1",
    "val_roc_auc",
    "val_pr_auc",
    "val_tp",
    "val_fp",
    "val_tn",
    "val_fn",
]


LONG_METRICS = ["loss", "accuracy", "precision", "recall", "f1", "roc_auc", "pr_auc"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train multimodal malware chunk classifier with PBS")

    parser.add_argument("--data-root", type=str, default="dataset", help="Dataset root containing manifest.csv")
    parser.add_argument("--manifest", type=str, default=None, help="Optional explicit path to manifest.csv")
    parser.add_argument("--out-dir", type=str, default="runs/multimodal_pbs", help="Output directory")

    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--steps-per-epoch", type=int, default=None)
    parser.add_argument("--num-workers", type=int, default=4)
    parser.add_argument("--seed", type=int, default=1337)

    parser.add_argument("--byt5-model", type=str, default="google/byt5-base")
    parser.add_argument("--max-byte-length", type=int, default=4096)
    parser.add_argument("--image-width", type=int, default=256, help="Width used by binary-to-image rendering")
    parser.add_argument("--image-size", type=int, default=224, help="ResNet input size")
    parser.add_argument("--fusion-dim", type=int, default=512)
    parser.add_argument("--dropout", type=float, default=0.25)

    parser.add_argument("--lr", type=float, default=2e-5)
    parser.add_argument("--vision-lr", type=float, default=1e-4)
    parser.add_argument("--head-lr", type=float, default=1e-4)
    parser.add_argument("--weight-decay", type=float, default=0.01)
    parser.add_argument("--grad-clip", type=float, default=1.0)

    parser.add_argument("--freeze-byt5", action="store_true", help="Freeze ByT5 encoder to reduce GPU memory")
    parser.add_argument("--freeze-resnet-backbone", action="store_true")
    parser.add_argument("--gradient-checkpointing", action="store_true")
    parser.add_argument("--amp", action="store_true", help="Use mixed precision on CUDA")
    parser.add_argument("--validate-files", action="store_true", help="Check every path in manifest before training")
    parser.add_argument("--no-tensorboard", action="store_true")
    parser.add_argument("--device", type=str, default="cuda" if torch.cuda.is_available() else "cpu")

    return parser.parse_args()


def seed_everything(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    torch.backends.cudnn.benchmark = True


def move_batch_to_device(batch: dict[str, Any], device: torch.device) -> dict[str, Any]:
    return {
        "input_ids": batch["input_ids"].to(device, non_blocking=True),
        "attention_mask": batch["attention_mask"].to(device, non_blocking=True),
        "images": batch["image"].to(device, non_blocking=True),
        "labels": batch["label"].to(device, non_blocking=True),
    }


def make_optimizer(model: MultiModalClassifier, args: argparse.Namespace) -> AdamW:
    byte_params = []
    vision_params = []
    head_params = []

    for name, param in model.named_parameters():
        if not param.requires_grad:
            continue
        if name.startswith("byte_encoder"):
            byte_params.append(param)
        elif name.startswith("vision_encoder"):
            vision_params.append(param)
        else:
            head_params.append(param)

    groups = []
    if byte_params:
        groups.append({"params": byte_params, "lr": args.lr})
    if vision_params:
        groups.append({"params": vision_params, "lr": args.vision_lr})
    if head_params:
        groups.append({"params": head_params, "lr": args.head_lr})

    return AdamW(groups, weight_decay=args.weight_decay)


def _safe_auc(y_true: list[int], y_score: list[float], kind: str) -> float:
    if len(set(y_true)) < 2:
        return float("nan")
    try:
        if kind == "roc":
            return float(roc_auc_score(y_true, y_score))
        if kind == "pr":
            return float(average_precision_score(y_true, y_score))
    except ValueError:
        return float("nan")
    raise ValueError(f"Unsupported AUC kind: {kind}")


def compute_metrics(y_true: list[int], y_prob: list[float], y_pred: list[int]) -> dict[str, float]:
    """ calculate thresholded metrics plus ROC-AUC and PR-AUC """
    tp = sum(1 for t, p in zip(y_true, y_pred) if t == 1 and p == 1)
    fp = sum(1 for t, p in zip(y_true, y_pred) if t == 0 and p == 1)
    tn = sum(1 for t, p in zip(y_true, y_pred) if t == 0 and p == 0)
    fn = sum(1 for t, p in zip(y_true, y_pred) if t == 1 and p == 0)

    metrics = {
        "accuracy": accuracy_score(y_true, y_pred),
        "precision": precision_score(y_true, y_pred, zero_division=0),
        "recall": recall_score(y_true, y_pred, zero_division=0),
        "f1": f1_score(y_true, y_pred, zero_division=0),
        "roc_auc": _safe_auc(y_true, y_prob, "roc"),
        "pr_auc": _safe_auc(y_true, y_prob, "pr"),
        "tp": tp,
        "fp": fp,
        "tn": tn,
        "fn": fn,
    }
    return {k: float(v) for k, v in metrics.items()}


def _count_batch_composition(batch: dict[str, Any], tracker: Counter) -> None:
    chunk_types = batch.get("chunk_type", [])
    if isinstance(chunk_types, tuple):
        chunk_types = list(chunk_types)
    labels = batch.get("label")
    if isinstance(labels, torch.Tensor):
        labels_list = [int(x) for x in labels.cpu().tolist()]
    else:
        labels_list = [int(x) for x in labels]

    for chunk_type in chunk_types:
        tracker[f"sampled_{str(chunk_type)}"] += 1
    for label in labels_list:
        if label == 0:
            tracker["sampled_benign"] += 1
        elif label == 1:
            tracker["sampled_malware"] += 1
    tracker["sampled_total"] += len(labels_list)


def train_one_epoch(
    *,
    model: MultiModalClassifier,
    loader: DataLoader,
    criterion: nn.Module,
    optimizer: torch.optim.Optimizer,
    scaler: GradScaler,
    device: torch.device,
    amp: bool,
    grad_clip: float,
    epoch: int,
) -> dict[str, float]:
    model.train()
    total_loss = 0.0
    all_true: list[int] = []
    all_pred: list[int] = []
    all_prob: list[float] = []
    composition = Counter()

    pbar = tqdm(loader, desc=f"train epoch {epoch}", leave=False)
    for raw_batch in pbar:
        _count_batch_composition(raw_batch, composition)
        batch = move_batch_to_device(raw_batch, device)
        optimizer.zero_grad(set_to_none=True)

        with autocast(enabled=amp):
            logits = model(
                input_ids=batch["input_ids"],
                attention_mask=batch["attention_mask"],
                images=batch["images"],
            )
            loss = criterion(logits, batch["labels"])

        scaler.scale(loss).backward()
        if grad_clip and grad_clip > 0:
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_(model.parameters(), grad_clip)
        scaler.step(optimizer)
        scaler.update()

        probs = torch.softmax(logits.detach(), dim=1)[:, 1]
        preds = logits.detach().argmax(dim=1)
        labels = batch["labels"].detach()

        total_loss += float(loss.item())
        all_prob.extend(probs.cpu().tolist())
        all_pred.extend(preds.cpu().tolist())
        all_true.extend(labels.cpu().tolist())

        pbar.set_postfix(loss=f"{loss.item():.4f}")

    metrics = compute_metrics(all_true, all_prob, all_pred)
    metrics["loss"] = total_loss / max(len(loader), 1)
    for key in ["sampled_total", "sampled_obj", "sampled_fixed", "sampled_benign", "sampled_malware"]:
        metrics[key] = float(composition.get(key, 0))
    return metrics


@torch.no_grad()
def evaluate(
    *,
    model: MultiModalClassifier,
    loader: DataLoader,
    criterion: nn.Module,
    device: torch.device,
    amp: bool,
    split_name: str,
) -> dict[str, float]:
    model.eval()
    total_loss = 0.0
    all_true: list[int] = []
    all_pred: list[int] = []
    all_prob: list[float] = []

    pbar = tqdm(loader, desc=split_name, leave=False)
    for raw_batch in pbar:
        batch = move_batch_to_device(raw_batch, device)
        with autocast(enabled=amp):
            logits = model(
                input_ids=batch["input_ids"],
                attention_mask=batch["attention_mask"],
                images=batch["images"],
            )
            loss = criterion(logits, batch["labels"])

        probs = torch.softmax(logits, dim=1)[:, 1]
        preds = logits.argmax(dim=1)
        labels = batch["labels"]

        total_loss += float(loss.item())
        all_prob.extend(probs.cpu().tolist())
        all_pred.extend(preds.cpu().tolist())
        all_true.extend(labels.cpu().tolist())

    metrics = compute_metrics(all_true, all_prob, all_pred)
    metrics["loss"] = total_loss / max(len(loader), 1)
    return metrics


def save_json(path: Path, obj: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2, sort_keys=True), encoding="utf-8")


def append_csv(path: Path, row: dict[str, Any], fieldnames: list[str] | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if fieldnames is None:
        fieldnames = list(row.keys())
    write_header = not path.exists()
    with path.open("a", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        if write_header:
            writer.writeheader()
        writer.writerow(row)


def append_history(path: Path, row: dict[str, Any]) -> None:
    append_csv(path, row)


def append_tracking_files(
    *,
    out_dir: Path,
    row: dict[str, Any],
    train_metrics: dict[str, float],
    val_metrics: dict[str, float],
) -> None:
    # save PBS metric files (both wide and long)
    append_csv(out_dir / "pbs_epoch_metrics.csv", row, fieldnames=TRACKING_COLUMNS)

    long_path = out_dir / "pbs_plot_series_long.csv"
    base = {
        "epoch": row["epoch"],
        "pbs_phase": row["pbs_phase"],
        "phase_index": row["phase_index"],
        "phase_epoch": row["phase_epoch"],
        "phase_start_epoch": row["phase_start_epoch"],
        "phase_end_epoch": row["phase_end_epoch"],
        "obj_ratio": row["obj_ratio"],
        "fixed_ratio": row["fixed_ratio"],
    }
    long_fields = list(base.keys()) + ["split", "metric", "value"]
    for split_name, metrics in [("train", train_metrics), ("val", val_metrics)]:
        for metric in LONG_METRICS:
            append_csv(
                long_path,
                {**base, "split": split_name, "metric": metric, "value": metrics.get(metric, float("nan"))},
                fieldnames=long_fields,
            )


def _phase_delta(values: list[float]) -> float:
    if not values:
        return float("nan")
    return float(values[-1] - values[0])


def write_phase_summary(out_dir: Path) -> None:
    # summarise metrics changed inside each PBS phase
    tracking_path = out_dir / "pbs_epoch_metrics.csv"
    if not tracking_path.exists():
        return

    import pandas as pd

    df = pd.read_csv(tracking_path)
    if df.empty:
        return

    rows: list[dict[str, Any]] = []
    metric_cols = [
        "train_precision",
        "train_recall",
        "train_f1",
        "train_pr_auc",
        "val_precision",
        "val_recall",
        "val_f1",
        "val_pr_auc",
    ]

    for phase, group in df.groupby("pbs_phase", sort=False):
        group = group.sort_values("epoch")
        row: dict[str, Any] = {
            "pbs_phase": phase,
            "start_epoch": int(group["epoch"].iloc[0]),
            "end_epoch": int(group["epoch"].iloc[-1]),
            "num_epochs": int(len(group)),
            "obj_ratio": float(group["obj_ratio"].iloc[0]),
            "fixed_ratio": float(group["fixed_ratio"].iloc[0]),
            "sampled_obj": int(group["sampled_obj"].sum()),
            "sampled_fixed": int(group["sampled_fixed"].sum()),
        }
        for metric in metric_cols:
            values = [float(v) for v in group[metric].tolist()]
            row[f"{metric}_start"] = values[0]
            row[f"{metric}_end"] = values[-1]
            row[f"{metric}_delta"] = _phase_delta(values)
            row[f"{metric}_mean"] = float(np.nanmean(values))
            row[f"{metric}_max"] = float(np.nanmax(values))
        rows.append(row)

    if rows:
        path = out_dir / "pbs_phase_summary.csv"
        with path.open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)


def main() -> None:
    args = parse_args()
    seed_everything(args.seed)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    save_json(out_dir / "args.json", vars(args))

    device = torch.device(args.device)
    amp = bool(args.amp and device.type == "cuda")
    if args.amp and device.type != "cuda":
        print("CUDA is not available, use without AMP.", file=sys.stderr)

    manifest_csv = Path(args.manifest) if args.manifest else Path(args.data_root) / "manifest.csv"
    summary = summarize_manifest(args.data_root, manifest_csv)
    save_json(out_dir / "manifest_summary.json", summary)
    print(json.dumps(summary, indent=2, default=str))

    byte_config = ByteEncodingConfig(max_length=args.max_byte_length)

    train_dataset = MalwareChunkDataset(
        data_root=args.data_root,
        manifest_csv=manifest_csv,
        split="train",
        image_size=args.image_size,
        image_width=args.image_width,
        byte_config=byte_config,
        fixed_only=False,
        validate_files=args.validate_files,
    )
    val_dataset = MalwareChunkDataset(
        data_root=args.data_root,
        manifest_csv=manifest_csv,
        split="val",
        image_size=args.image_size,
        image_width=args.image_width,
        byte_config=byte_config,
        fixed_only=True,
        validate_files=args.validate_files,
    )
    test_dataset = MalwareChunkDataset(
        data_root=args.data_root,
        manifest_csv=manifest_csv,
        split="test",
        image_size=args.image_size,
        image_width=args.image_width,
        byte_config=byte_config,
        fixed_only=True,
        validate_files=args.validate_files,
    )

    print("train counts:", train_dataset.counts())
    print("val counts:", val_dataset.counts())
    print("test counts:", test_dataset.counts())

    val_loader = DataLoader(
        val_dataset,
        batch_size=args.batch_size,
        shuffle=False,
        num_workers=args.num_workers,
        pin_memory=device.type == "cuda",
    )
    test_loader = DataLoader(
        test_dataset,
        batch_size=args.batch_size,
        shuffle=False,
        num_workers=args.num_workers,
        pin_memory=device.type == "cuda",
    )

    model = MultiModalClassifier(
        byt5_model_name=args.byt5_model,
        fusion_dim=args.fusion_dim,
        dropout=args.dropout,
        freeze_byt5=args.freeze_byt5,
        freeze_resnet_backbone=args.freeze_resnet_backbone,
        gradient_checkpointing=args.gradient_checkpointing,
    ).to(device)

    criterion = nn.CrossEntropyLoss()
    optimizer = make_optimizer(model, args)
    scaler = GradScaler(enabled=amp)

    writer = None
    if not args.no_tensorboard and SummaryWriter is not None:
        writer = SummaryWriter(log_dir=str(out_dir / "tensorboard"))

    best_val_f1 = -1.0
    best_val_pr_auc = -1.0
    best_ckpt_path = out_dir / "best_model.pt"
    last_ckpt_path = out_dir / "last_model.pt"
    history_path = out_dir / "history.csv"

    for epoch in range(1, args.epochs + 1):
        obj_ratio, fixed_ratio, phase = pbs_ratios(epoch)
        phase_index, phase_start, phase_end, phase_epoch = pbs_phase_bounds(epoch)
        sampler = PBSBatchSampler(
            chunk_types=train_dataset.chunk_types,
            labels=train_dataset.labels,
            batch_size=args.batch_size,
            epoch=epoch,
            steps_per_epoch=args.steps_per_epoch,
            seed=args.seed,
        )
        train_loader = DataLoader(
            train_dataset,
            batch_sampler=sampler,
            num_workers=args.num_workers,
            pin_memory=device.type == "cuda",
        )

        print(
            f"\nEpoch {epoch}/{args.epochs} | PBS phase={phase} | "
            f"obj:fixed={obj_ratio:.0%}:{fixed_ratio:.0%} | batches={len(train_loader)}"
        )

        train_metrics = train_one_epoch(
            model=model,
            loader=train_loader,
            criterion=criterion,
            optimizer=optimizer,
            scaler=scaler,
            device=device,
            amp=amp,
            grad_clip=args.grad_clip,
            epoch=epoch,
        )
        val_metrics = evaluate(
            model=model,
            loader=val_loader,
            criterion=criterion,
            device=device,
            amp=amp,
            split_name="val",
        )

        row = {
            "epoch": epoch,
            "pbs_phase": phase,
            "phase_index": phase_index,
            "phase_epoch": phase_epoch,
            "phase_start_epoch": phase_start,
            "phase_end_epoch": phase_end,
            "obj_ratio": obj_ratio,
            "fixed_ratio": fixed_ratio,
            "sampled_total": int(train_metrics.get("sampled_total", 0)),
            "sampled_obj": int(train_metrics.get("sampled_obj", 0)),
            "sampled_fixed": int(train_metrics.get("sampled_fixed", 0)),
            "sampled_benign": int(train_metrics.get("sampled_benign", 0)),
            "sampled_malware": int(train_metrics.get("sampled_malware", 0)),
            **{f"train_{k}": v for k, v in train_metrics.items() if not k.startswith("sampled_")},
            **{f"val_{k}": v for k, v in val_metrics.items()},
        }
        append_history(history_path, row)
        append_tracking_files(out_dir=out_dir, row=row, train_metrics=train_metrics, val_metrics=val_metrics)
        write_phase_summary(out_dir)

        if writer is not None:
            for k, v in train_metrics.items():
                if not k.startswith("sampled_"):
                    writer.add_scalar(f"train/{k}", v, epoch)
            for k, v in val_metrics.items():
                writer.add_scalar(f"val/{k}", v, epoch)
            writer.add_scalar("pbs/obj_ratio", obj_ratio, epoch)
            writer.add_scalar("pbs/fixed_ratio", fixed_ratio, epoch)
            writer.add_scalar("pbs/sampled_obj", row["sampled_obj"], epoch)
            writer.add_scalar("pbs/sampled_fixed", row["sampled_fixed"], epoch)

        print(
            f"train loss={train_metrics['loss']:.4f} "
            f"P={train_metrics['precision']:.4f} R={train_metrics['recall']:.4f} "
            f"F1={train_metrics['f1']:.4f} PR-AUC={train_metrics['pr_auc']:.4f} | "
            f"val loss={val_metrics['loss']:.4f} "
            f"P={val_metrics['precision']:.4f} R={val_metrics['recall']:.4f} "
            f"F1={val_metrics['f1']:.4f} PR-AUC={val_metrics['pr_auc']:.4f}"
        )

        checkpoint = {
            "epoch": epoch,
            "model_state_dict": model.state_dict(),
            "optimizer_state_dict": optimizer.state_dict(),
            "args": vars(args),
            "val_metrics": val_metrics,
            "train_metrics": train_metrics,
            "pbs_phase": phase,
            "obj_ratio": obj_ratio,
            "fixed_ratio": fixed_ratio,
        }
        torch.save(checkpoint, last_ckpt_path)

        # Keep F1 as the default checkpoint target, but tie-break on PR-AUC.
        if (val_metrics["f1"] > best_val_f1) or (
            val_metrics["f1"] == best_val_f1 and val_metrics["pr_auc"] > best_val_pr_auc
        ):
            best_val_f1 = val_metrics["f1"]
            best_val_pr_auc = val_metrics["pr_auc"]
            torch.save(checkpoint, best_ckpt_path)
            print(f"Saved new best checkpoint: {best_ckpt_path}")

    write_phase_summary(out_dir)

    if best_ckpt_path.exists():
        ckpt = torch.load(best_ckpt_path, map_location=device)
        model.load_state_dict(ckpt["model_state_dict"])
        print(f"Loaded best checkpoint from epoch {ckpt['epoch']} for final test evaluation.")

    test_metrics = evaluate(
        model=model,
        loader=test_loader,
        criterion=criterion,
        device=device,
        amp=amp,
        split_name="test",
    )
    save_json(out_dir / "test_metrics.json", test_metrics)
    print("test metrics:", json.dumps(test_metrics, indent=2))

    if writer is not None:
        for k, v in test_metrics.items():
            writer.add_scalar(f"test/{k}", v, args.epochs)
        writer.close()


if __name__ == "__main__":
    main()
