#!/usr/bin/env python3
"""
Wrapper for local/Ollama/Llama scoring.
"""

from __future__ import annotations

import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from score_slices import main


if __name__ == "__main__":
    # Default to Ollama if caller did not provide a backend.
    if "--backend" not in sys.argv:
        sys.argv.extend(["--backend", "ollama"])

    main()