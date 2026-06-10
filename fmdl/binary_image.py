"""
Transform binary file to grayscale image.

2 approaches:
- Call an external `bin_to_img(file_path, width=..., pad=...)` function for on-disk operation
- Transform in-memory.
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional

import numpy as np
from PIL import Image
import torch
from torchvision import transforms as T

try:
    from torchvision.transforms import InterpolationMode
except Exception: 
    InterpolationMode = None


_EXTERNAL_BIN_TO_IMG = None
_EXTERNAL_BIN_TO_IMG_ERROR: Optional[Exception] = None

try: 
    from bin_to_img import bin_to_img as _imported_bin_to_img  # type: ignore
    _EXTERNAL_BIN_TO_IMG = _imported_bin_to_img
except Exception as exc: 
    _EXTERNAL_BIN_TO_IMG_ERROR = exc


def ondisk_bin_to_img(file_path: str | Path, width: int = 256, pad: bool = True) -> Image.Image:
    """
    Convert one binary file into a grayscale PIL image in memory.

    This never executes the file. It only reads bytes.
    """
    file_path = Path(file_path)
    byte_array = np.fromfile(file_path, dtype=np.uint8)

    if byte_array.size == 0:
        raise ValueError(f"Empty binary chunk: {file_path}")

    remainder = byte_array.size % width
    if remainder:
        if pad:
            byte_array = np.pad(byte_array, (0, width - remainder), mode="constant", constant_values=0)
        else:
            byte_array = byte_array[: byte_array.size - remainder]
            if byte_array.size == 0:
                raise ValueError(
                    f"File {file_path} is smaller than width={width} and pad=False would remove all bytes."
                )

    image_array = byte_array.reshape((-1, width))
    return Image.fromarray(image_array, mode="L")


def binary_to_pil(file_path: str | Path, width: int = 256, pad: bool = True) -> Image.Image:
    """
    Preferred conversion entrypoint for the dataset.

    It calls a user-provided `bin_to_img` function when available. Otherwise it
    uses the in-memory fallback above.
    """
    if _EXTERNAL_BIN_TO_IMG is not None:
        image = _EXTERNAL_BIN_TO_IMG(file_path, width=width, pad=pad)
        if not isinstance(image, Image.Image):
            raise TypeError(
                "bin_to_img(...) must return a PIL.Image.Image. "
                "The old save-to-disk script is not suitable for on-the-fly training."
            )
        return image.convert("L")

    return fallback_bin_to_img(file_path, width=width, pad=pad)


class AddGaussianNoise:
    """Light Gaussian noise for tensor images in [0, 1]."""

    def __init__(self, std: float = 0.015, p: float = 0.5) -> None:
        self.std = float(std)
        self.p = float(p)

    def __call__(self, image: torch.Tensor) -> torch.Tensor:
        if torch.rand(1).item() >= self.p:
            return image
        noisy = image + torch.randn_like(image) * self.std
        return torch.clamp(noisy, 0.0, 1.0)


def build_image_transform(
    *,
    image_size: int = 224,
    train: bool = False,
    augment_fixed: bool = False,
) -> T.Compose:
    """
    Build image transforms.

    Augmentation is intended only for fixed-size chunks during training.
    Object-based chunks and all validation/test chunks use deterministic
    resize + normalization.
    """
    interpolation = InterpolationMode.BILINEAR if InterpolationMode is not None else 2

    if train and augment_fixed:
        return T.Compose(
            [
                T.Resize((image_size + 16, image_size + 16), interpolation=interpolation),
                T.RandomCrop((image_size, image_size), padding=4, padding_mode="edge"),
                T.ColorJitter(brightness=0.10, contrast=0.10),
                T.RandomPerspective(distortion_scale=0.04, p=0.25, interpolation=interpolation),
                T.RandomAffine(
                    degrees=2,
                    translate=(0.02, 0.02),
                    shear=(-2, 2),
                    interpolation=interpolation,
                    fill=0,
                ),
                T.ToTensor(),
                AddGaussianNoise(std=0.015, p=0.5),
                T.Normalize(mean=[0.5], std=[0.5]),
            ]
        )

    return T.Compose(
        [
            T.Resize((image_size, image_size), interpolation=interpolation),
            T.ToTensor(),
            T.Normalize(mean=[0.5], std=[0.5]),
        ]
    )
