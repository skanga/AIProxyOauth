#!/usr/bin/env python3
"""Run live compatibility checks against an authenticated AIProxyOauth server."""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


DEFAULT_BASE_URL = "http://127.0.0.1:10531/v1"
DEFAULT_MODEL = "gpt-5.3-codex-spark"


class CompatibilityError(RuntimeError):
    """Raised when a live compatibility check fails."""


def post_json(
    base_url: str,
    path: str,
    body: dict[str, Any],
    api_key: str | None,
) -> dict[str, Any]:
    """POST a JSON object and return the decoded JSON response."""
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    url = f"{base_url.rstrip('/')}/{path.lstrip('/')}"
    request = Request(
        url,
        data=json.dumps(body, separators=(",", ":")).encode("utf-8"),
        headers=headers,
        method="POST",
    )

    try:
        with urlopen(request) as response:
            payload = response.read().decode("utf-8")
    except HTTPError as error:
        details = error.read().decode("utf-8", errors="replace")
        suffix = f": {details}" if details else ""
        raise CompatibilityError(
            f"POST {url} returned HTTP {error.code}{suffix}"
        ) from error
    except URLError as error:
        raise CompatibilityError(f"POST {url} failed: {error.reason}") from error

    try:
        decoded = json.loads(payload)
    except json.JSONDecodeError as error:
        raise CompatibilityError(f"POST {url} returned invalid JSON") from error
    if not isinstance(decoded, dict):
        raise CompatibilityError(f"POST {url} returned a non-object JSON response")
    return decoded


def run_checks(base_url: str, model: str, api_key: str | None) -> None:
    """Run the Responses API text, function-call, and continuation checks."""
    text = post_json(
        base_url,
        "responses",
        {"model": model, "input": "Reply with exactly hello."},
        api_key,
    )
    output = text.get("output")
    if text.get("status") != "completed" or not isinstance(output, list) or not output:
        raise CompatibilityError(
            "Responses string-input smoke test returned no completed output."
        )

    tool = {
        "type": "function",
        "name": "lookup",
        "description": "Look up a string.",
        "parameters": {
            "type": "object",
            "properties": {"query": {"type": "string"}},
            "required": ["query"],
            "additionalProperties": False,
        },
    }
    call = post_json(
        base_url,
        "responses",
        {
            "model": model,
            "input": "Call lookup with query hello.",
            "tools": [tool],
            "tool_choice": {"type": "function", "name": "lookup"},
        },
        api_key,
    )
    call_output = call.get("output")
    function_call = (
        next(
            (
                item
                for item in call_output
                if isinstance(item, dict) and item.get("type") == "function_call"
            ),
            None,
        )
        if isinstance(call_output, list)
        else None
    )
    if function_call is None:
        raise CompatibilityError("The Codex backend completed without a function_call.")

    response_id = call.get("id")
    call_id = function_call.get("call_id")
    if not response_id or not call_id:
        raise CompatibilityError(
            "The function-call response omitted its response id or call id."
        )

    continued = post_json(
        base_url,
        "responses",
        {
            "model": model,
            "previous_response_id": response_id,
            "input": [
                {
                    "type": "function_call_output",
                    "call_id": call_id,
                    "output": "hello-result",
                }
            ],
        },
        api_key,
    )
    if continued.get("status") != "completed":
        raise CompatibilityError("Function-call continuation did not complete.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run live compatibility checks against AIProxyOauth."
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument(
        "--api-key",
        default=os.environ.get("AIPROXY_API_KEY"),
        help="Proxy API key (defaults to AIPROXY_API_KEY).",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        run_checks(args.base_url, args.model, args.api_key)
    except CompatibilityError as error:
        print(f"Live compatibility checks failed: {error}", file=sys.stderr)
        return 1

    print(f"Live compatibility checks passed for {args.model} at {args.base_url}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
