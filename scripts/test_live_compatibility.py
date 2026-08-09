#!/usr/bin/env python3
"""Offline unit tests for the opt-in live compatibility runner."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("live-compatibility.py")
SPEC = importlib.util.spec_from_file_location("live_compatibility", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
live = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(live)


class LiveCompatibilityTest(unittest.TestCase):
    def test_parses_chat_sse_and_requires_one_done(self) -> None:
        payload = (
            'data: {"choices":[{"delta":{"role":"assistant"}}]}\n\n'
            'data: {"choices":[{"delta":{"content":"hello"}}]}\n\n'
            "data: [DONE]\n\n"
        )

        text = live.extract_chat_stream_text(payload)

        self.assertEqual("hello", text)

    def test_rejects_duplicate_done_terminators(self) -> None:
        with self.assertRaises(live.CompatibilityError):
            live.extract_chat_stream_text("data: [DONE]\n\ndata: [DONE]\n\n")

    def test_claude_requires_explicit_live_opt_in(self) -> None:
        self.assertFalse(live.claude_live_enabled("anthropic", False, {}))
        self.assertTrue(live.claude_live_enabled("anthropic", True, {}))
        self.assertTrue(
            live.claude_live_enabled("anthropic", False, {"AIPROXY_LIVE_CLAUDE": "1"})
        )
        self.assertTrue(live.claude_live_enabled("codex", False, {}))

    def test_runs_text_stream_tool_call_and_continuation_for_any_provider(self) -> None:
        call = {
            "id": "resp_1",
            "status": "completed",
            "output": [{
                "type": "function_call",
                "call_id": "call_1",
                "name": "lookup",
                "arguments": '{"query":"hello"}',
            }],
        }
        completed = {"id": "resp_2", "status": "completed", "output": []}
        with patch.object(live, "post_sse", return_value=("hello", "raw")) as stream:
            with patch.object(live, "post_json", side_effect=[completed, call, completed]) as post:
                live.run_checks("http://127.0.0.1:10531/v1", "claude-test", None,
                                "anthropic")

        self.assertEqual(1, stream.call_count)
        self.assertEqual(3, post.call_count)
        continuation = post.call_args_list[2].args[2]
        self.assertEqual("resp_1", continuation["previous_response_id"])
        self.assertEqual("call_1", continuation["input"][0]["call_id"])

    def test_parses_native_anthropic_stream_without_done(self) -> None:
        payload = (
            'event: message_start\ndata: {"type":"message_start","message":{"usage":{"input_tokens":2}}}\n\n'
            'event: content_block_delta\ndata: {"type":"content_block_delta","delta":{"type":"text_delta","text":"hello"}}\n\n'
            'event: message_stop\ndata: {"type":"message_stop"}\n\n'
        )

        self.assertEqual("hello", live.extract_anthropic_stream_text(payload))
        with self.assertRaises(live.CompatibilityError):
            live.extract_anthropic_stream_text(payload + "data: [DONE]\n\n")

    def test_runs_native_anthropic_text_models_tool_and_continuation(self) -> None:
        tool_call = {
            "id": "msg_1",
            "type": "message",
            "role": "assistant",
            "content": [{"type": "tool_use", "id": "tool_1", "name": "lookup",
                         "input": {"query": "hello"}}],
        }
        text = {"id": "msg_0", "type": "message", "role": "assistant",
                "content": [{"type": "text", "text": "hello"}]}
        completed = {"id": "msg_2", "type": "message", "role": "assistant",
                     "content": [{"type": "text", "text": "hello-result"}]}
        with patch.object(live, "get_json", return_value={"data": [{"id": "claude-test"}]}) as get:
            with patch.object(live, "post_anthropic_sse", return_value=("hello", "raw")) as stream:
                with patch.object(live, "post_anthropic_json",
                                  side_effect=[text, tool_call, completed]) as post:
                    live.run_native_anthropic_checks(
                        "http://127.0.0.1:10531/v1", "claude-test", "proxy-key")

        self.assertEqual(1, get.call_count)
        self.assertEqual(1, stream.call_count)
        self.assertEqual(3, post.call_count)
        continuation = post.call_args_list[2].args[2]
        self.assertEqual("tool_1", continuation["messages"][2]["content"][0]["tool_use_id"])


if __name__ == "__main__":
    unittest.main()
