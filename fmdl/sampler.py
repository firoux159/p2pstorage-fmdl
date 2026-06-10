"""
Progressive Balance Shifting batch sampler.
"""

from __future__ import annotations

import math
import random
from collections import defaultdict
from typing import Iterator, Sequence

from torch.utils.data import Sampler


def pbs_phase_bounds(epoch: int) -> tuple[int, int, int, int]:
    """
    return (phase_index, phase_start_epoch, phase_end_epoch, phase_epoch)
    phase_epoch is the 1-indexed epoch number inside the current PBS phase
    for the final Align phase, phase_end_epoch is 20 by design
    """
    if epoch <= 3:
        return 1, 1, 3, epoch
    if epoch <= 10:
        return 2, 4, 10, epoch - 3
    return 3, 11, 20, epoch - 10

def pbs_ratios(epoch: int) -> tuple[float, float, str]:
    # return (obj_ratio, fixed_ratio, phase_name) for a 1-indexed epoch.
    if epoch <= 3:
        return 0.50, 0.50, "warmup"
    if epoch <= 10:
        return 0.40, 0.60, "stabilise"
    return 0.30, 0.70, "align"


class PBSBatchSampler(Sampler[list[int]]):
    """
    Batch sampler with obj/fixed ratios per batch and class balancing.
    """

    def __init__(
        self,
        *,
        chunk_types: Sequence[str],
        labels: Sequence[int],
        batch_size: int,
        epoch: int,
        steps_per_epoch: int | None = None,
        seed: int = 1337,
        drop_last: bool = True,
    ) -> None:
        if batch_size < 4:
            raise ValueError("batch_size should be at least 4 for PBS + class balancing.")

        self.chunk_types = list(chunk_types)
        self.labels = [int(x) for x in labels]
        self.batch_size = int(batch_size)
        self.epoch = int(epoch)
        self.obj_ratio, self.fixed_ratio, self.phase = pbs_ratios(epoch)
        self.steps_per_epoch = steps_per_epoch or math.ceil(len(self.labels) / self.batch_size)
        self.seed = int(seed)
        self.drop_last = bool(drop_last)

        self.by_group: dict[tuple[str, int], list[int]] = defaultdict(list)
        for idx, (chunk_type, label) in enumerate(zip(self.chunk_types, self.labels)):
            if chunk_type not in {"obj", "fixed"}:
                raise ValueError(f"Unsupported chunk_type={chunk_type!r}; expected obj or fixed.")
            if label not in {0, 1}:
                raise ValueError(f"Unsupported label={label!r}; expected 0 or 1.")
            self.by_group[(chunk_type, label)].append(idx)

        missing = [group for group in [("obj", 0), ("obj", 1), ("fixed", 0), ("fixed", 1)] if not self.by_group[group]]
        if missing:
            raise ValueError(
                "PBS needs at least one sample for each (chunk_type, label) group in train split. "
                f"Missing groups: {missing}"
            )

    def __len__(self) -> int:
        return self.steps_per_epoch

    @staticmethod
    def _split_count(total: int) -> tuple[int, int]:
        """Return approximately half negative and half positive counts."""
        neg = total // 2
        pos = total - neg
        return neg, pos

    def _draw_group(self, rng: random.Random, chunk_type: str, count: int) -> list[int]:
        neg_count, pos_count = self._split_count(count)
        out = []
        out.extend(rng.choices(self.by_group[(chunk_type, 0)], k=neg_count))
        out.extend(rng.choices(self.by_group[(chunk_type, 1)], k=pos_count))
        return out

    def __iter__(self) -> Iterator[list[int]]:
        rng = random.Random(self.seed + self.epoch * 1_000_003)

        fixed_count = round(self.batch_size * self.fixed_ratio)
        obj_count = self.batch_size - fixed_count

        # need to avoid degenerate zero-count groups using small batch size
        fixed_count = max(2, fixed_count)
        obj_count = max(2, obj_count)
        while fixed_count + obj_count > self.batch_size:
            if fixed_count > obj_count:
                fixed_count -= 1
            else:
                obj_count -= 1

        for _ in range(self.steps_per_epoch):
            batch = []
            batch.extend(self._draw_group(rng, "obj", obj_count))
            batch.extend(self._draw_group(rng, "fixed", fixed_count))
            rng.shuffle(batch)
            if len(batch) == self.batch_size or not self.drop_last:
                yield batch
