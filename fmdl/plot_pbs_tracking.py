"""
Plot PBS phase plots from pbs_epoch_metrics.csv.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd


PHASE_LABELS = {
    "warmup": "Warm-up",
    "stabilise": "Stabilise",
    "align": "Align",
}


PHASE_SPANS = [
    ("warmup", 1, 3),
    ("stabilise", 4, 10),
    ("align", 11, 20),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Plot PBS precision/recall/PR-AUC tracking files")
    parser.add_argument("--csv", type=str, default="runs/multimodal_pbs/pbs_epoch_metrics.csv")
    parser.add_argument("--out-dir", type=str, default=None)
    parser.add_argument("--title-prefix", type=str, default="FMDL")
    parser.add_argument("--dpi", type=int, default=220)
    parser.add_argument("--show", action="store_true")
    return parser.parse_args()


def add_phase_background(ax: plt.Axes, max_epoch: int) -> None:
    # The different alpha values make the phase boundaries visible even in grayscale.
    alphas = {"warmup": 0.28, "stabilise": 0.16, "align": 0.07}
    for phase, start, end in PHASE_SPANS:
        if start > max_epoch:
            continue
        visible_end = min(end, max_epoch)
        ax.axvspan(start - 0.5, visible_end + 0.5, alpha=alphas[phase])
        midpoint = (start + visible_end) / 2
        ymin, ymax = ax.get_ylim()
        ax.text(
            midpoint,
            ymin + 0.18 * (ymax - ymin),
            PHASE_LABELS[phase],
            ha="center",
            va="center",
            fontsize=11,
        )


def setup_axis(ax: plt.Axes, df: pd.DataFrame, ylabel: str) -> None:
    max_epoch = int(df["epoch"].max())
    ax.set_xlim(1, max_epoch)
    ax.set_xticks(list(range(1, max_epoch + 1)))
    ax.set_ylim(0.0, 1.0)
    ax.set_xlabel("Epoch")
    ax.set_ylabel(ylabel)
    ax.grid(True, linestyle="--", alpha=0.5)
    add_phase_background(ax, max_epoch=max_epoch)


def plot_pr_curves(df: pd.DataFrame, out_dir: Path, title_prefix: str, dpi: int) -> Path:
    fig, ax = plt.subplots(figsize=(12, 6))

    ax.plot(df["epoch"], df["train_precision"], marker="o", label="Train precision")
    ax.plot(df["epoch"], df["train_recall"], marker="o", label="Train recall")
    ax.plot(df["epoch"], df["val_precision"], marker="s", linestyle="--", label="Val precision")
    ax.plot(df["epoch"], df["val_recall"], marker="s", linestyle="--", label="Val recall")

    setup_axis(ax, df, "Precision / Recall")
    ax.set_title(f"{title_prefix} — Precision and Recall Progression with PBS Phases")
    ax.legend(loc="lower right")
    fig.tight_layout()

    out_path = out_dir / "pbs_precision_recall_progression.png"
    fig.savefig(out_path, dpi=dpi)
    plt.close(fig)
    return out_path


def plot_metric(df: pd.DataFrame, out_dir: Path, title_prefix: str, metric: str, ylabel: str, dpi: int) -> Path:
    fig, ax = plt.subplots(figsize=(12, 6))

    ax.plot(df["epoch"], df[f"train_{metric}"], marker="o", label=f"Train {ylabel}")
    ax.plot(df["epoch"], df[f"val_{metric}"], marker="s", linestyle="--", label=f"Val {ylabel}")

    setup_axis(ax, df, ylabel)
    ax.set_title(f"{title_prefix} — Training-time {ylabel} Progression with PBS Phases")
    ax.legend(loc="lower right")
    fig.tight_layout()

    out_path = out_dir / f"pbs_{metric}_progression.png"
    fig.savefig(out_path, dpi=dpi)
    plt.close(fig)
    return out_path


def plot_phase_delta_bars(df: pd.DataFrame, out_dir: Path, title_prefix: str, dpi: int) -> Path:
    # plot within-phase change in validation precision and recall.
    rows = []
    for phase, group in df.groupby("pbs_phase", sort=False):
        group = group.sort_values("epoch")
        rows.append(
            {
                "phase": PHASE_LABELS.get(phase, phase),
                "val_precision_delta": float(group["val_precision"].iloc[-1] - group["val_precision"].iloc[0]),
                "val_recall_delta": float(group["val_recall"].iloc[-1] - group["val_recall"].iloc[0]),
                "val_pr_auc_delta": float(group["val_pr_auc"].iloc[-1] - group["val_pr_auc"].iloc[0]),
            }
        )
    summary = pd.DataFrame(rows)

    fig, ax = plt.subplots(figsize=(10, 5))
    x = range(len(summary))
    width = 0.25
    ax.bar([i - width for i in x], summary["val_precision_delta"], width=width, label="Val precision Δ")
    ax.bar(list(x), summary["val_recall_delta"], width=width, label="Val recall Δ")
    ax.bar([i + width for i in x], summary["val_pr_auc_delta"], width=width, label="Val PR-AUC Δ")
    ax.axhline(0, linewidth=1)
    ax.set_xticks(list(x))
    ax.set_xticklabels(summary["phase"].tolist())
    ax.set_ylabel("End minus start")
    ax.set_title(f"{title_prefix} — Within-phase Validation Metric Changes")
    ax.grid(True, axis="y", linestyle="--", alpha=0.5)
    ax.legend(loc="best")
    fig.tight_layout()

    out_path = out_dir / "pbs_phase_metric_deltas.png"
    fig.savefig(out_path, dpi=dpi)
    plt.close(fig)
    return out_path


def main() -> None:
    args = parse_args()
    csv_path = Path(args.csv)
    if not csv_path.exists():
        raise FileNotFoundError(f"PBS tracking CSV not found: {csv_path}")

    out_dir = Path(args.out_dir) if args.out_dir else csv_path.parent / "plots"
    out_dir.mkdir(parents=True, exist_ok=True)

    df = pd.read_csv(csv_path).sort_values("epoch")
    
    required = {
        "epoch",
        "pbs_phase",
        "train_precision",
        "train_recall",
        "train_pr_auc",
        "train_f1",
        "val_precision",
        "val_recall",
        "val_pr_auc",
        "val_f1",
    }

    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"Missing required columns in {csv_path}: {sorted(missing)}")

    paths = [
        plot_pr_curves(df, out_dir, args.title_prefix, args.dpi),
        plot_metric(df, out_dir, args.title_prefix, "pr_auc", "PR-AUC", args.dpi),
        plot_metric(df, out_dir, args.title_prefix, "f1", "F1", args.dpi),
        plot_phase_delta_bars(df, out_dir, args.title_prefix, args.dpi),
    ]

    print("Generated plots:")
    for path in paths:
        print(f"- {path}")

    if args.show:
        # interractive
        plt.show()


if __name__ == "__main__":
    main()
