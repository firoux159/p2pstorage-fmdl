from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, List, Optional

import config
from .types import Message


class ScoringClient:
    backend: str
    model: str

    def complete(self, messages: List[Message]) -> str:
        raise NotImplementedError


@dataclass
class OpenAIClient(ScoringClient):
    model: str
    api_key: Optional[str] = None
    base_url: Optional[str] = None
    temperature: float = 0.0
    timeout: int = 60
    retries: int = 2
    max_response_tokens: int = 128
    backend: str = "openai"

    def __post_init__(self) -> None:
        try:
            from openai import OpenAI  # type: ignore
        except ImportError as exc:
            raise RuntimeError("openai package is not installed. Try: pip install openai") from exc

        key = self.api_key or os.getenv(config.OpenAIConfig().api_key_env)
        if not key:
            raise RuntimeError(f"{config.OpenAIConfig().api_key_env} missing")
        kwargs: dict[str, Any] = {"api_key": key, "timeout": self.timeout, "max_retries": self.retries}
        if self.base_url:
            kwargs["base_url"] = self.base_url
        self._client = OpenAI(**kwargs)

    def complete(self, messages: List[Message]) -> str:
        resp = self._client.chat.completions.create(
            model=self.model,
            messages=messages,
            temperature=self.temperature,
            max_tokens=self.max_response_tokens,
        )
        content = resp.choices[0].message.content
        if not content:
            raise RuntimeError("OpenAI returned an empty response")
        return content.strip()


@dataclass
class OpenAICompatibleHTTPClient(ScoringClient):
    # plain HTTP client for local OpenAI-compatible /v1/chat/completions endpoints

    model: str
    base_url: str
    temperature: float = 0.0
    timeout: int = 30
    retries: int = 3
    backoff: float = 2.0
    max_response_tokens: int = 128
    backend: str = "openai-compatible"

    def complete(self, messages: List[Message]) -> str:
        try:
            import requests  # type: ignore
        except ImportError as exc:
            raise RuntimeError("requests package is not installed. Try: pip install requests") from exc

        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": self.temperature,
            "max_tokens": self.max_response_tokens,
            "n": 1,
        }
        last_err: Exception | None = None
        for attempt in range(self.retries + 1):
            try:
                resp = requests.post(self.base_url, json=payload, timeout=self.timeout)
                resp.raise_for_status()
                body = resp.json()
                content = body["choices"][0]["message"]["content"]
                if not content:
                    raise RuntimeError(f"empty response: {body}")
                return str(content).strip()
            except Exception as exc:
                last_err = exc
                if attempt < self.retries:
                    time.sleep(self.backoff * (attempt + 1))
        raise RuntimeError(f"OpenAI-compatible endpoint failed: {last_err}")


@dataclass
class OllamaClient(ScoringClient):
    model: str
    url: str
    temperature: float = 0.0
    timeout: int = 120
    retries: int = 1
    max_response_tokens: int = 128
    backend: str = "ollama"

    def complete(self, messages: List[Message]) -> str:
        payload = {
            "model": self.model,
            "messages": messages,
            "stream": False,
            "options": {
                "temperature": self.temperature,
                "num_predict": self.max_response_tokens,
            },
        }
        data = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.url,
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST",
        )

        last_err: Exception | None = None
        for attempt in range(self.retries + 1):
            try:
                with urllib.request.urlopen(request, timeout=self.timeout) as resp:
                    body = json.loads(resp.read().decode("utf-8"))
                content = body.get("message", {}).get("content", "")
                if not content:
                    raise RuntimeError(f"Ollama returned no message content: {body}")
                return str(content).strip()
            except (urllib.error.URLError, TimeoutError, RuntimeError, json.JSONDecodeError) as exc:
                last_err = exc
                if attempt < self.retries:
                    time.sleep(1.5 * (attempt + 1))
        raise RuntimeError(
            "Could not call native Ollama. Check `ollama serve`, the model name, and URL. "
            f"Last error: {last_err}"
        )


class LlamaCppClient(ScoringClient):
    backend = "llama-cpp"

    def __init__(
        self,
        model_path: str,
        model: str | None = None,
        chat_format: str = "llama-3",
        n_ctx: int = 8192,
        n_gpu_layers: int = -1,
        temperature: float = 0.0,
        max_response_tokens: int = 128,
        verbose: bool = False,
    ) -> None:
        if not model_path:
            raise RuntimeError("model_path is required for llama-cpp backend")
        try:
            from llama_cpp import Llama  # type: ignore
        except ImportError as exc:
            raise RuntimeError("llama-cpp-python is not installed. Try: pip install llama-cpp-python") from exc
        self.model = model or model_path
        self.temperature = temperature
        self.max_response_tokens = max_response_tokens
        self._llm = Llama(
            model_path=model_path,
            n_ctx=n_ctx,
            n_gpu_layers=n_gpu_layers,
            chat_format=chat_format,
            verbose=verbose,
        )

    def complete(self, messages: List[Message]) -> str:
        resp = self._llm.create_chat_completion(
            messages=messages,
            temperature=self.temperature,
            max_tokens=self.max_response_tokens,
        )
        return str(resp["choices"][0]["message"]["content"]).strip()


def build_client(args: Any) -> ScoringClient:
    backend = args.backend
    scoring_cfg = config.ScoringConfig()
    max_response_tokens = args.max_response_tokens or scoring_cfg.max_response_tokens

    if backend == "openai":
        cfg = config.OpenAIConfig()
        return OpenAIClient(
            model=args.model or cfg.model,
            api_key=args.api_key,
            base_url=args.base_url or cfg.base_url or None,
            temperature=args.temperature if args.temperature is not None else cfg.temperature,
            timeout=args.timeout or cfg.timeout,
            retries=args.retries if args.retries is not None else cfg.retries,
            max_response_tokens=max_response_tokens,
        )

    if backend == "openai-compatible":
        cfg = config.LLMConfig()
        return OpenAICompatibleHTTPClient(
            model=args.model or cfg.model,
            base_url=args.base_url or cfg.base_url,
            temperature=args.temperature if args.temperature is not None else cfg.temperature,
            timeout=args.timeout or cfg.timeout,
            retries=args.retries if args.retries is not None else cfg.retries,
            backoff=cfg.backoff,
            max_response_tokens=max_response_tokens,
        )

    if backend == "ollama":
        cfg = config.OllamaConfig()
        return OllamaClient(
            model=args.model or cfg.model,
            url=args.ollama_url or cfg.url,
            temperature=args.temperature if args.temperature is not None else cfg.temperature,
            timeout=args.timeout or cfg.timeout,
            retries=args.retries if args.retries is not None else cfg.retries,
            max_response_tokens=max_response_tokens,
        )

    if backend == "llama-cpp":
        cfg = config.LlamaCppConfig()
        return LlamaCppClient(
            model_path=str(args.model_path or cfg.model_path),
            model=args.model,
            chat_format=args.chat_format or cfg.chat_format,
            n_ctx=args.n_ctx or cfg.n_ctx,
            n_gpu_layers=args.n_gpu_layers if args.n_gpu_layers is not None else cfg.n_gpu_layers,
            temperature=args.temperature if args.temperature is not None else 0.0,
            max_response_tokens=max_response_tokens,
            verbose=args.verbose,
        )

    raise ValueError(f"unsupported backend: {backend}")
