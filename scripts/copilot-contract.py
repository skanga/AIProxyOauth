#!/usr/bin/env python3
"""Opt-in investigation of the Copilot HTTP contract; not a production backend."""

import argparse
import json
import os
import re
import sys
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

REFERENCE = "microsoft/vscode@b4abde1c921a634d4caef8492e4f67f13eba6444"
ENDPOINTS = ("/chat/completions", "/responses", "/v1/messages")
MAX_BYTES = 8 * 1024 * 1024


class ContractError(RuntimeError):
    """A safe diagnostic that never includes response bodies or credentials."""


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request_json(url, token=None, body=None):
    headers = {"Accept": "application/json", "Content-Type": "application/json",
               "User-Agent": "AIProxyOauth-contract-probe", "X-Request-Id": str(uuid.uuid4())}
    if token:
        headers["Authorization"] = "Bearer " + token
    if url.endswith("/copilot_internal/user"):
        headers["X-GitHub-Api-Version"] = "2025-04-01"
    if url.endswith("/v1/messages"):
        headers["anthropic-version"] = "2023-06-01"
        headers["X-GitHub-Api-Version"] = "2026-01-09"
    request = Request(url, data=json.dumps(body).encode() if body is not None else None,
                      headers=headers, method="POST" if body is not None else "GET")
    try:
        with build_opener(NoRedirects()).open(request, timeout=60) as response:
            data = response.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES:
            raise ContractError("Response exceeded the probe size limit")
        result = json.loads(data)
    except HTTPError as error:
        status = error.code
        error.close()
        raise ContractError(f"Upstream returned HTTP {status}; body suppressed") from None
    except (URLError, OSError, ValueError):
        raise ContractError("Network failure or invalid JSON response; details suppressed") from None
    if not isinstance(result, dict):
        raise ContractError("Expected an upstream JSON object")
    return result


def github_host(value):
    value = value.lower()
    if value != "github.com" and not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.ghe\.com", value):
        raise ContractError("Host must be github.com or a tenant.ghe.com hostname")
    return value


def discovery_url(host):
    return "https://api." + github_host(host) + "/copilot_internal/user"


def load_copilot_config(path, host):
    """Read only a user-selected CLI config; never search for CLI credentials."""
    with path.open(encoding="utf-8-sig") as source:
        raw = source.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ContractError("Copilot config exceeded the probe size limit")
    pattern = r'"(?:\\.|[^"\\])*"|//[^\r\n]*|/\*[\s\S]*?\*/'
    config = json.loads(re.sub(pattern, lambda match:
                              match.group() if match.group().startswith('"') else ' ', raw))
    account = config["lastLoggedInUser"]
    account_uri = "https://" + github_host(host)
    if account["host"].rstrip("/") != account_uri:
        raise ContractError("Selected config account does not match --host")
    token = config["authTokens"][account["host"] + ":" + account["login"]]["token"]
    if not isinstance(token, str):
        raise ContractError("Copilot config token must be a string")
    return token.strip()


def inference_base(value, host="github.com"):
    if not isinstance(value, str):
        raise ContractError("Discovery did not return an inference endpoint")
    host = github_host(host)
    try:
        uri = urlsplit(value)
        name = uri.hostname or ""
        allowed = name.endswith(".githubcopilot.com")
        if host != "github.com":
            allowed = allowed or name.endswith("." + host)
        if (uri.scheme != "https" or not allowed or uri.username or uri.password
                or uri.port not in (None, 443) or uri.path not in ("", "/")
                or uri.query or uri.fragment or any(ord(char) <= 32 for char in value)):
            raise ValueError()
    except ValueError:
        raise ContractError("Discovery returned an untrusted inference endpoint") from None
    return value.rstrip("/")


def probe(request, host, token, model=None, endpoint=None, *, allow_unadvertised=False):
    host = github_host(host)
    if (model is None) != (endpoint is None) or (endpoint and endpoint not in ENDPOINTS):
        raise ContractError("Inference requires an explicit model and supported endpoint")
    user = request(discovery_url(host), token, None)
    base = inference_base(user.get("endpoints", {}).get("api"), host)
    catalog = request(base + "/models", token, None)
    if not isinstance(catalog.get("data"), list):
        raise ContractError("Model catalog has no data array")
    models = []
    for entry in catalog["data"]:
        if not isinstance(entry, dict):
            raise ContractError("Invalid model catalog entry")
        name = entry.get("id")
        endpoints = entry.get("supported_endpoints", [])
        if not isinstance(name, str) or not re.fullmatch(r"[a-zA-Z0-9_.:/-]{1,200}", name):
            raise ContractError("Invalid model identifier")
        if not isinstance(endpoints, list):
            raise ContractError("Invalid supported_endpoints metadata")
        capabilities = entry.get("capabilities", {})
        supports = capabilities.get("supports", {}) if isinstance(capabilities, dict) else {}
        models.append({"id": name, "endpoint_metadata_present": "supported_endpoints" in entry,
                       "supported_endpoints": [e for e in ENDPOINTS if e in endpoints],
                       "supports": {key: supports[key] for key in
                           ("streaming", "tool_calls", "parallel_tool_calls", "vision", "thinking")
                           if isinstance(supports, dict) and isinstance(supports.get(key), bool)}})
    report = {"reference": REFERENCE, "host_kind": "github.com" if host == "github.com" else "ghe.com",
              "discovery_verified": True, "inference_verified": False, "models": models}
    if model is None:
        return report
    chosen = next((entry for entry in models if entry["id"] == model), None)
    if chosen is None or (endpoint not in chosen["supported_endpoints"]
                          and not (allow_unadvertised and not chosen["endpoint_metadata_present"])):
        raise ContractError("Selected model/endpoint is not advertised; no fallback attempted")
    body = {"model": model, "stream": False}
    if endpoint == "/responses":
        body.update(input="Reply with OK.", max_output_tokens=128)
    else:
        body["messages"] = [{"role": "user", "content": "Reply with OK."}]
        body["max_tokens"] = 128
    output = request(base + endpoint, token, body)
    if endpoint == "/chat/completions":
        text = "".join(c.get("message", {}).get("content", "") or "" for c in output.get("choices", []))
    elif endpoint == "/responses":
        if output.get("status") != "completed":
            raise ContractError("Inference did not complete")
        text = "".join(p.get("text", "") for item in output.get("output", [])
                       if item.get("type") == "message" for p in item.get("content", [])
                       if p.get("type") == "output_text")
    else:
        if not output.get("stop_reason"):
            raise ContractError("Inference did not complete")
        text = "".join(p.get("text", "") for p in output.get("content", []) if p.get("type") == "text")
    if not text.strip():
        raise ContractError("Inference returned no text")
    report.update(inference_verified=True, tested_model=model, tested_endpoint=endpoint,
                  endpoint_advertised=endpoint in chosen["supported_endpoints"])
    return report


def device_login(request, host, client_id, scope, announce, sleep, monotonic):
    host = github_host(host)
    body = {"client_id": client_id}
    if scope:
        body["scope"] = scope
    attempt = request("https://" + host + "/login/device/code", None, body)
    try:
        device_code, user_code = attempt["device_code"], attempt["user_code"]
        verification = attempt["verification_uri"]
        interval, lifetime = attempt["interval"], attempt["expires_in"]
        if (not isinstance(device_code, str) or not device_code
                or not isinstance(user_code, str) or not re.fullmatch(r"[A-Z0-9-]{4,32}", user_code)
                or verification != "https://" + host + "/login/device"
                or type(interval) is not int or not 1 <= interval <= 60
                or type(lifetime) is not int or not 1 <= lifetime <= 1800):
            raise ValueError()
    except (KeyError, ValueError, TypeError):
        raise ContractError("Invalid device authorization response") from None
    announce(f"Open {verification} and enter {user_code}")
    deadline = monotonic() + lifetime
    while monotonic() + interval < deadline:
        sleep(interval)
        if monotonic() >= deadline:
            break
        result = request("https://" + host + "/login/oauth/access_token", None,
                         {"client_id": client_id, "device_code": device_code,
                          "grant_type": "urn:ietf:params:oauth:grant-type:device_code"})
        token = result.get("access_token")
        if isinstance(token, str) and token and not any(c.isspace() for c in token):
            return token
        error = result.get("error")
        if error == "slow_down":
            interval += 5
        elif error != "authorization_pending":
            raise ContractError("Device authorization denied, expired, or failed")
    raise ContractError("Device authorization expired")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="github.com or tenant.ghe.com")
    auth = parser.add_mutually_exclusive_group(required=True)
    auth.add_argument("--token-file", type=Path, help="File containing only a GitHub OAuth token")
    auth.add_argument("--token-env", help="Name of environment variable containing the token")
    auth.add_argument("--copilot-config", type=Path, help="Explicit CLI JSON/JSONC config; no automatic discovery")
    auth.add_argument("--device-login", action="store_true", help="Keep obtained token in memory only")
    parser.add_argument("--client-id", help="Explicit public OAuth client ID for device login")
    parser.add_argument("--scope", default="", help="Explicit OAuth scopes; none requested by default")
    parser.add_argument("--model", help="Exact upstream model ID; omission means discovery only")
    parser.add_argument("--endpoint", choices=ENDPOINTS)
    parser.add_argument("--allow-unadvertised-endpoint", action="store_true",
                        help="Investigate the explicit endpoint when catalog metadata is absent")
    args = parser.parse_args(argv)
    try:
        host = github_host(args.host)
        if bool(args.model) != bool(args.endpoint):
            raise ContractError("Supply both --model and --endpoint, or neither")
        if args.allow_unadvertised_endpoint and not args.model:
            raise ContractError("Endpoint investigation requires --model and --endpoint")
        if args.device_login:
            if not args.client_id:
                raise ContractError("Device login requires an explicit --client-id")
            token = device_login(request_json, host, args.client_id, args.scope,
                                 lambda message: print(message, file=sys.stderr, flush=True),
                                 time.sleep, time.monotonic)
        elif args.copilot_config:
            token = load_copilot_config(args.copilot_config, host)
        elif args.token_file:
            with args.token_file.open(encoding="utf-8") as source:
                token = source.read(16385).strip()
        else:
            token = os.environ.get(args.token_env, "").strip()
        if not token or len(token) > 16384 or any(c.isspace() for c in token):
            raise ContractError("Missing or invalid explicit OAuth token")
        report = probe(request_json, host, token, args.model, args.endpoint,
                       allow_unadvertised=args.allow_unadvertised_endpoint)
        report["device_login_verified"] = args.device_login
        print(json.dumps(report, indent=2))
        return 0
    except ContractError as error:
        print(str(error), file=sys.stderr)
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        print("Credential read or contract validation failed; details suppressed", file=sys.stderr)
    except KeyboardInterrupt:
        print("Cancelled", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
