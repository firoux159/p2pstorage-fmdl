"""
Multimodal malware classifier: ByT5 encoder + one-channel ResNet18 fused via concatenation MLP
"""

from __future__ import annotations

import torch
from torch import nn
from transformers import T5EncoderModel
from torchvision.models import ResNet18_Weights, resnet18


def masked_mean_pool(last_hidden_state: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
    mask = attention_mask.unsqueeze(-1).to(last_hidden_state.dtype)
    summed = (last_hidden_state * mask).sum(dim=1)
    denom = mask.sum(dim=1).clamp(min=1.0)
    return summed / denom


class MultimodalMalwareClassifier(nn.Module):
    def __init__(
        self,
        *,
        byt5_model_name: str = "google/byt5-base",
        num_classes: int = 2,
        fusion_dim: int = 512,
        dropout: float = 0.25,
        freeze_byt5: bool = False,
        freeze_resnet_backbone: bool = False,
        gradient_checkpointing: bool = False,
    ) -> None:
        super().__init__()

        self.byte_encoder = T5EncoderModel.from_pretrained(byt5_model_name)
        if gradient_checkpointing and hasattr(self.byte_encoder, "gradient_checkpointing_enable"):
            self.byte_encoder.gradient_checkpointing_enable()

        byte_hidden_size = int(self.byte_encoder.config.d_model)
        self.byte_projection = nn.Sequential(
            nn.LayerNorm(byte_hidden_size),
            nn.Linear(byte_hidden_size, fusion_dim),
            nn.GELU(),
            nn.Dropout(dropout),
        )

        if freeze_byt5:
            for param in self.byte_encoder.parameters():
                param.requires_grad = False

        weights = ResNet18_Weights.DEFAULT
        self.vision_encoder = resnet18(weights=weights)

        # Convert RGB conv1 to one-channel conv1 by summing pretrained RGB weights.
        old_conv = self.vision_encoder.conv1
        new_conv = nn.Conv2d(
            in_channels=1,
            out_channels=old_conv.out_channels,
            kernel_size=old_conv.kernel_size,
            stride=old_conv.stride,
            padding=old_conv.padding,
            bias=False,
        )
        with torch.no_grad():
            new_conv.weight.copy_(old_conv.weight.sum(dim=1, keepdim=True))
        self.vision_encoder.conv1 = new_conv

        vision_hidden_size = self.vision_encoder.fc.in_features
        self.vision_encoder.fc = nn.Identity()
        self.vision_projection = nn.Sequential(
            nn.LayerNorm(vision_hidden_size),
            nn.Linear(vision_hidden_size, fusion_dim),
            nn.GELU(),
            nn.Dropout(dropout),
        )

        if freeze_resnet_backbone:
            for name, param in self.vision_encoder.named_parameters():
                if not name.startswith("fc"):
                    param.requires_grad = False

        self.classifier = nn.Sequential(
            nn.LayerNorm(fusion_dim * 2),
            nn.Linear(fusion_dim * 2, fusion_dim),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(fusion_dim, num_classes),
        )

    def forward(
        self,
        *,
        input_ids: torch.Tensor,
        attention_mask: torch.Tensor,
        images: torch.Tensor,
    ) -> torch.Tensor:
        byte_outputs = self.byte_encoder(input_ids=input_ids, attention_mask=attention_mask)
        byte_features = masked_mean_pool(byte_outputs.last_hidden_state, attention_mask)
        byte_features = self.byte_projection(byte_features)

        vision_features = self.vision_encoder(images)
        vision_features = self.vision_projection(vision_features)

        fused = torch.cat([byte_features, vision_features], dim=1)
        return self.classifier(fused)
