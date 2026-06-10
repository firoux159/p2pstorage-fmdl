"""
Plot validation vs test precision-recall curves from saved inference logs.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any

import matplotlib.pyplot as plt
import numpy as np
from sklearn.metrics import average_precision_score, precision_recall_curve


REQUIRED_COLUMNS = {"split", "label", "malware_probability"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate validation/test PR curves from saved observation-level prediction logs."
    )

    parser.add_argument(
        "--run-dir",
        type=Path,
        default=Path("runs/multimodal_pbs"),
        help="Training output directory. Used for default input/output paths.",
    )
    parser.add_argument(
        "--predictions-csv",
        type=Path,
        default=None,
        help="Prediction CSV from run_val_test_inference.py. Defaults to <run-dir>/validation_test_pr_predictions.csv.",
    )
    parser.add_argument(
        "--summary-json",
        type=Path,
        default=None,
        help="Optional summary JSON from run_val_test_inference.py. Defaults to <run-dir>/validation_test_pr_predictions_summary.json if it exists.",
    )
    parser.add_argument(
        "--validation-split-name",
        type=str,
        default="validation",
        help="Split value to use as validation data in the prediction CSV.",
    )
    parser.add_argument(
        "--test-split-name",
        type=str,
        default="test",
        help="Split value to use as test data in the prediction CSV.",
    )
    parser.add_argument(
        "--prevalence",
        type=float,
        required=True,
        help="Static prevalence baseline. Use x.xx for x.xx%% or 0.0xxx as a fraction.",
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
        "--epoch-label",
        type=str,
        default=None,
        help="Label to show in legend. Defaults to summary JSON epoch_label, then 'saved inference'.",
    )
    parser.add_argument(
        "--save-curve-csv",
        action="store_true",
        help="Also save plotted PR curve points to <run-dir>/validation_test_pr_curve_points.csv unless --curve-csv-out is set.",
    )
    parser.add_argument(
        "--curve-csv-out",
        type=Path,
        default=None,
        help="Optional output CSV for plotted PR curve points.",
    )

    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def normalize_prevalence(prevalence: float) -> float:
    if prevalence > 1.0:
        prevalence = prevalence / 100.0
    if not (0.0 <= prevalence <= 1.0):
        raise ValueError("prevalence must be either a fraction in [0, 1] or a percent in [0, 100].")
    return prevalence


def read_predictions(path: Path) -> dict[str, tuple[np.ndarray, np.ndarray]]:
    if not path.exists():
        raise FileNotFoundError(f"Prediction CSV not found: {path}")

    labels_by_split: dict[str, list[int]] = {}
    scores_by_split: dict[str, list[float]] = {}

    with path.open("r", newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fieldnames = set(reader.fieldnames or [])
        missing = REQUIRED_COLUMNS - fieldnames
        if missing:
            raise ValueError(f"Prediction CSV is missing required columns: {sorted(missing)}")

        for row_number, row in enumerate(reader, start=2):
            split_name = row["split"]
            try:
                label = int(row["label"])
                score = float(row["malware_probability"])
            except ValueError as exc:
                raise ValueError(f"Bad label/probability value on CSV row {row_number}: {row}") from exc

            labels_by_split.setdefault(split_name, []).append(label)
            scores_by_split.setdefault(split_name, []).append(score)

    return {
        split_name: (
            np.asarray(labels_by_split[split_name], dtype=np.int64),
            np.asarray(scores_by_split[split_name], dtype=np.float64),
        )
        for split_name in labels_by_split
    }


def make_curve(y_true: np.ndarray, y_score: np.ndarray, split_name: str) -> dict[str, Any]:
    if len(y_true) == 0:
        raise ValueError(f"Split {split_name!r} has no observations.")
    if len(np.unique(y_true)) < 2:
        raise ValueError(f"Split {split_name!r} contains only one class. A PR curve is not meaningful.")

    precision, recall, thresholds = precision_recall_curve(y_true, y_score)

    # sklearn returns recall from high to low. Reverse it so the plot moves left-to-right.
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


def save_curve_csv(path: Path, curves_by_split: dict[str, dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["split", "point", "recall", "precision"])
        writer.writeheader()
        for split_name, curve in curves_by_split.items():
            for i, (r, p) in enumerate(zip(curve["recall"].tolist(), curve["precision"].tolist())):
                writer.writerow(
                    {
                        "split": split_name,
                        "point": i,
                        "recall": float(r),
                        "precision": float(p),
                    }
                )


def plot_curves(
    *,
    val_curve: dict[str, Any],
    test_curve: dict[str, Any],
    prevalence: float,
    epoch_label: str,
    title: str,
    out_path: Path,
) -> None:
    prevalence = normalize_prevalence(prevalence)

    fig, ax = plt.subplots(figsize=(12, 8), dpi=128)

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

    predictions_csv = args.predictions_csv or (args.run_dir / "validation_test_pr_predictions.csv")
    out_path = args.out or (args.run_dir / "validation_vs_test_pr_curves.png")
    summary_json = args.summary_json or (args.run_dir / "validation_test_pr_predictions_summary.json")
    summary = load_json(summary_json)
    epoch_label = args.epoch_label or summary.get("epoch_label") or "saved inference"

    outputs = read_predictions(predictions_csv)
    available_splits = sorted(outputs.keys())

    if args.validation_split_name not in outputs:
        raise ValueError(
            f"Validation split {args.validation_split_name!r} was not found in {predictions_csv}. "
            f"Available splits: {available_splits}"
        )
    if args.test_split_name not in outputs:
        raise ValueError(
            f"Test split {args.test_split_name!r} was not found in {predictions_csv}. "
            f"Available splits: {available_splits}"
        )

    val_y, val_prob = outputs[args.validation_split_name]
    test_y, test_prob = outputs[args.test_split_name]

    val_curve = make_curve(val_y, val_prob, args.validation_split_name)
    test_curve = make_curve(test_y, test_prob, args.test_split_name)

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

    if args.save_curve_csv or args.curve_csv_out is not None:
        curve_csv_out = args.curve_csv_out or (args.run_dir / "validation_test_pr_curve_points.csv")
        save_curve_csv(
            curve_csv_out,
            {
                args.validation_split_name: val_curve,
                args.test_split_name: test_curve,
            },
        )
        print(f"Saved curve points: {curve_csv_out}")


if __name__ == "__main__":
    main()
