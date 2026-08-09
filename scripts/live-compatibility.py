#!/usr/bin/env python3
"""Run opt-in live compatibility checks against an authenticated proxy."""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections.abc import Mapping
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


DEFAULT_BASE_URL = "http://127.0.0.1:10531/v1"
DEFAULT_CODEX_MODEL = "gpt-5.3-codex-spark"
DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-5"
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
REQUEST_TIMEOUT_SECONDS = 60


class CompatibilityError(RuntimeError):
    """Raised when a live compatibility check fails."""


def _headers(api_key: str | None, accept: str) -> dict[str, str]:
    headers = {"Content-Type": "application/json", "Accept": accept}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    return headers


def _anthropic_headers(api_key: str | None, accept: str) -> dict[str, str]:
    headers = _headers(api_key, accept)
    headers["anthropic-version"] = "2023-06-01"
    return headers


def _read_bounded(response: Any) -> str:
    payload = response.read(MAX_RESPONSE_BYTES + 1)
    if len(payload) > MAX_RESPONSE_BYTES:
        raise CompatibilityError(
            f"Response exceeded the {MAX_RESPONSE_BYTES}-byte compatibility limit."
        )
    return payload.decode("utf-8", errors="replace")


def _request(
    base_url: str,
    path: str,
    body: dict[str, Any],
    api_key: str | None,
    accept: str,
) -> str:
    url = f"{base_url.rstrip('/')}/{path.lstrip('/')}"
    request = Request(
        url,
        data=json.dumps(body, separators=(",", ":")).encode("utf-8"),
        headers=_headers(api_key, accept),
        method="POST",
    )
    try:
        with urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            return _read_bounded(response)
    except HTTPError as error:
        details = _read_bounded(error)
        suffix = f": {details}" if details else ""
        raise CompatibilityError(
            f"POST {url} returned HTTP {error.code}{suffix}"
        ) from error
    except URLError as error:
        raise CompatibilityError(f"POST {url} failed: {error.reason}") from error


def _anthropic_request(
    base_url: str,
    path: str,
    api_key: str | None,
    accept: str,
    body: dict[str, Any] | None = None,
) -> str:
    url = f"{base_url.rstrip('/')}/{path.lstrip('/')}"
    method = "POST" if body is not None else "GET"
    request = Request(
        url,
        data=(json.dumps(body, separators=(",", ":")).encode("utf-8")
              if body is not None else None),
        headers=_anthropic_headers(api_key, accept),
        method=method,
    )
    try:
        with urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            return _read_bounded(response)
    except HTTPError as error:
        details = _read_bounded(error)
        suffix = f": {details}" if details else ""
        raise CompatibilityError(
            f"{method} {url} returned HTTP {error.code}{suffix}"
        ) from error
    except URLError as error:
        raise CompatibilityError(f"{method} {url} failed: {error.reason}") from error


def post_json(
    base_url: str,
    path: str,
    body: dict[str, Any],
    api_key: str | None,
) -> dict[str, Any]:
    """POST a JSON object and return the decoded JSON response."""
    payload = _request(base_url, path, body, api_key, "application/json")
    url = f"{base_url.rstrip('/')}/{path.lstrip('/')}"
    try:
        decoded = json.loads(payload)
    except json.JSONDecodeError as error:
        raise CompatibilityError(f"POST {url} returned invalid JSON") from error
    if not isinstance(decoded, dict):
        raise CompatibilityError(f"POST {url} returned a non-object JSON response")
    return decoded


def _decode_object(payload: str, operation: str) -> dict[str, Any]:
    try:
        decoded = json.loads(payload)
    except json.JSONDecodeError as error:
        raise CompatibilityError(f"{operation} returned invalid JSON") from error
    if not isinstance(decoded, dict):
        raise CompatibilityError(f"{operation} returned a non-object JSON response")
    return decoded


def get_json(base_url: str, path: str, api_key: str | None) -> dict[str, Any]:
    payload = _anthropic_request(base_url, path, api_key, "application/json")
    return _decode_object(payload, f"GET {path}")


def post_anthropic_json(
    base_url: str,
    path: str,
    body: dict[str, Any],
    api_key: str | None,
) -> dict[str, Any]:
    payload = _anthropic_request(
        base_url, path, api_key, "application/json", body)
    return _decode_object(payload, f"POST {path}")


def _sse_data_payloads(payload: str) -> list[str]:
    events: list[str] = []
    data_lines: list[str] = []
    for line in payload.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if not line:
            if data_lines:
                events.append("\n".join(data_lines))
                data_lines = []
            continue
        if line.startswith("data:"):
            data_lines.append(line[5:].lstrip(" "))
    if data_lines:
        events.append("\n".join(data_lines))
    return events


def extract_chat_stream_text(payload: str) -> str:
    """Extract assistant text and validate one Chat Completions terminator."""
    parts: list[str] = []
    done_count = 0
    for data in _sse_data_payloads(payload):
        if data == "[DONE]":
            done_count += 1
            continue
        try:
            event = json.loads(data)
        except json.JSONDecodeError as error:
            raise CompatibilityError("Chat stream contained invalid JSON data.") from error
        choices = event.get("choices") if isinstance(event, dict) else None
        if not isinstance(choices, list):
            continue
        for choice in choices:
            delta = choice.get("delta") if isinstance(choice, dict) else None
            content = delta.get("content") if isinstance(delta, dict) else None
            if isinstance(content, str):
                parts.append(content)
    if done_count != 1:
        raise CompatibilityError(
            f"Chat stream contained {done_count} [DONE] terminators; expected exactly one."
        )
    text = "".join(parts)
    if not text:
        raise CompatibilityError("Chat stream completed without assistant text.")
    return text


def extract_anthropic_stream_text(payload: str) -> str:
    """Extract native text while preserving Anthropic terminal semantics."""
    parts: list[str] = []
    stops = 0
    for data in _sse_data_payloads(payload):
        if data == "[DONE]":
            raise CompatibilityError("Native Anthropic stream contained an OpenAI [DONE].")
        try:
            event = json.loads(data)
        except json.JSONDecodeError as error:
            raise CompatibilityError("Native Anthropic stream contained invalid JSON.") from error
        if not isinstance(event, dict):
            continue
        if event.get("type") == "message_stop":
            stops += 1
        delta = event.get("delta")
        if isinstance(delta, dict) and delta.get("type") == "text_delta":
            text = delta.get("text")
            if isinstance(text, str):
                parts.append(text)
    if stops != 1:
        raise CompatibilityError(
            f"Native Anthropic stream contained {stops} message_stop events; expected one."
        )
    text = "".join(parts)
    if not text:
        raise CompatibilityError("Native Anthropic stream completed without text.")
    return text


def post_sse(
    base_url: str,
    path: str,
    body: dict[str, Any],
    api_key: str | None,
) -> tuple[str, str]:
    """POST a streaming request and return extracted text plus the raw stream."""
    payload = _request(base_url, path, body, api_key, "text/event-stream")
    return extract_chat_stream_text(payload), payload


def post_anthropic_sse(
    base_url: str,
    path: str,
    body: dict[str, Any],
    api_key: str | None,
) -> tuple[str, str]:
    payload = _anthropic_request(
        base_url, path, api_key, "text/event-stream", body)
    return extract_anthropic_stream_text(payload), payload


def claude_live_enabled(
    provider: str,
    command_line_opt_in: bool,
    environ: Mapping[str, str],
) -> bool:
    """Require explicit consent before a check can spend Claude quota."""
    if provider != "anthropic":
        return True
    environment_opt_in = environ.get("AIPROXY_LIVE_CLAUDE", "").lower()
    return command_line_opt_in or environment_opt_in in {"1", "true", "yes"}


def run_checks(
    base_url: str,
    model: str,
    api_key: str | None,
    provider: str,
) -> None:
    """Run text streaming, Responses, tool-call, and continuation checks."""
    post_sse(
        base_url,
        "chat/completions",
        {
            "model": model,
            "messages": [{"role": "user", "content": "Reply with exactly hello."}],
            "stream": True,
        },
        api_key,
    )

    text_response = post_json(
        base_url,
        "responses",
        {"model": model, "input": "Reply with exactly hello."},
        api_key,
    )
    if text_response.get("status") != "completed":
        raise CompatibilityError("Responses string-input smoke test did not complete.")

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
        raise CompatibilityError(
            f"The {provider} backend completed without a function_call."
        )

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


def run_native_anthropic_checks(
    base_url: str,
    model: str,
    api_key: str | None,
) -> None:
    """Run native catalog, sync/stream text, tool, and tool-result checks."""
    catalog = get_json(base_url, "models?limit=100", api_key)
    data = catalog.get("data")
    if not isinstance(data, list) or not data:
        raise CompatibilityError("Native Anthropic model discovery returned no models.")

    post_anthropic_sse(
        base_url,
        "messages",
        {"model": model, "max_tokens": 64, "stream": True,
         "messages": [{"role": "user", "content": "Reply with exactly hello."}]},
        api_key,
    )
    text = post_anthropic_json(
        base_url,
        "messages",
        {"model": model, "max_tokens": 64,
         "messages": [{"role": "user", "content": "Reply with exactly hello."}]},
        api_key,
    )
    if text.get("type") != "message" or not text.get("content"):
        raise CompatibilityError("Native Anthropic synchronous text returned no content.")

    tool = {
        "name": "lookup",
        "description": "Look up a string.",
        "input_schema": {
            "type": "object",
            "properties": {"query": {"type": "string"}},
            "required": ["query"],
            "additionalProperties": False,
        },
    }
    user = {"role": "user", "content": "Call lookup with query hello."}
    call = post_anthropic_json(
        base_url,
        "messages",
        {"model": model, "max_tokens": 128, "messages": [user],
         "tools": [tool], "tool_choice": {"type": "tool", "name": "lookup"}},
        api_key,
    )
    content = call.get("content")
    tool_use = next(
        (block for block in content
         if isinstance(block, dict) and block.get("type") == "tool_use"),
        None,
    ) if isinstance(content, list) else None
    if tool_use is None or not tool_use.get("id"):
        raise CompatibilityError("Native Anthropic response omitted its tool_use block.")

    continued = post_anthropic_json(
        base_url,
        "messages",
        {"model": model, "max_tokens": 128, "messages": [
            user,
            {"role": "assistant", "content": content},
            {"role": "user", "content": [{
                "type": "tool_result", "tool_use_id": tool_use["id"],
                "content": "hello-result",
            }]},
        ]},
        api_key,
    )
    if continued.get("type") != "message" or not continued.get("content"):
        raise CompatibilityError("Native Anthropic tool continuation did not complete.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run live compatibility checks against AIProxyOauth."
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument(
        "--provider",
        choices=("codex", "anthropic"),
        default=os.environ.get("AIPROXY_LIVE_PROVIDER", "codex"),
    )
    parser.add_argument(
        "--api-format",
        choices=("openai", "anthropic"),
        default="openai",
        help="Client API surface to verify (default: openai).",
    )
    parser.add_argument(
        "--model",
        help="Model to check (defaults depend on --provider).",
    )
    parser.add_argument(
        "--api-key",
        default=os.environ.get("AIPROXY_API_KEY"),
        help="Proxy API key (defaults to AIPROXY_API_KEY).",
    )
    parser.add_argument(
        "--allow-live-claude",
        action="store_true",
        help="Explicitly permit checks that consume Claude quota.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not claude_live_enabled(args.provider, args.allow_live_claude, os.environ):
        print(
            "Claude live checks require --allow-live-claude or "
            "AIPROXY_LIVE_CLAUDE=1.",
            file=sys.stderr,
        )
        return 2

    if args.model:
        model = args.model
    elif args.provider == "anthropic":
        model = os.environ.get("AIPROXY_CLAUDE_MODEL", DEFAULT_ANTHROPIC_MODEL)
    else:
        model = os.environ.get("AIPROXY_CODEX_MODEL", DEFAULT_CODEX_MODEL)

    try:
        if args.api_format == "anthropic":
            if args.provider != "anthropic":
                raise CompatibilityError(
                    "The native Anthropic format requires --provider anthropic.")
            run_native_anthropic_checks(args.base_url, model, args.api_key)
        else:
            run_checks(args.base_url, model, args.api_key, args.provider)
    except CompatibilityError as error:
        print(f"Live compatibility checks failed: {error}", file=sys.stderr)
        return 1

    print(
        f"Live compatibility checks passed for {args.api_format} "
        f"{args.provider}/{model} "
        f"at {args.base_url}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
