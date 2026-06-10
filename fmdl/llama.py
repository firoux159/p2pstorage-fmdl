"""
Backward-compatible Llama scorer facade.
"""

from __future__ import annotations

import re
from typing import List

import config
from scoring.clients import OpenAICompatibleHTTPClient

_yes = re.compile(r"^\s*YES\b", re.I)


class LlamaAPI:
    # compatibility wrapper around an OpenAI-compatible local Llama endpoint."""
    def __init__(self, cfg: config.LLMConfig | None = None):
        self.cfg = cfg or config.LLMConfig()
        self.client = OpenAICompatibleHTTPClient(
            model=self.cfg.model,
            base_url=self.cfg.base_url,
            temperature=self.cfg.temperature,
            timeout=self.cfg.timeout,
            retries=self.cfg.retries,
            backoff=self.cfg.backoff,
            max_response_tokens=8,
        )

    def probs(self, prompts: List[str]) -> List[float]:
        out: List[float] = []
        for prompt in prompts:
            messages = [
                {"role": "system", "content": "Reply ONLY YES or NO if the chunk is malicious."},
                {"role": "user", "content": prompt[:8000]},
            ]
            raw = self.client.complete(messages)
            out.append(1.0 if _yes.match(raw) else 0.0)
        return out


class LocalLlama:
    def __init__(self, *args, **kwargs):
        raise RuntimeError(
            "LocalLlama was intentionally removed from the unified import path. "
            "Use score_slices.py --backend llama-cpp, --backend ollama, or "
            "--backend openai-compatible."
        )
