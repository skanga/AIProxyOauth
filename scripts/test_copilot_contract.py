"""Offline behavioral tests for the Copilot contract investigation."""

import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import HTTPError

SPEC = importlib.util.spec_from_file_location(
    "copilot_contract", Path(__file__).with_name("copilot-contract.py"))
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)


class CopilotContractTest(unittest.TestCase):
    def test_host_isolation(self):
        self.assertEqual("github.com", contract.github_host("github.com"))
        self.assertEqual("https://api.github.com/copilot_internal/user",
                         contract.discovery_url("github.com"))
        self.assertEqual("https://api.acme.ghe.com/copilot_internal/user",
                         contract.discovery_url("acme.ghe.com"))
        for host in ["https://github.com", "github.com.evil.test", "ghe.com",
                     "acme.ghe.com/path", "user@acme.ghe.com", "localhost"]:
            with self.subTest(host=host), self.assertRaises(contract.ContractError):
                contract.github_host(host)

    def test_discovered_endpoint_cannot_leak_token(self):
        self.assertEqual("https://api.githubcopilot.com",
                         contract.inference_base("https://api.githubcopilot.com/"))
        for endpoint in ["http://api.githubcopilot.com", "https://evil.test",
                         "https://api.githubcopilot.com.evil.test", "https://user@api.githubcopilot.com",
                         "https://api.githubcopilot.com/path", "https://api.githubcopilot.com?q=secret"]:
            with self.subTest(endpoint=endpoint), self.assertRaises(contract.ContractError):
                contract.inference_base(endpoint)

    def responses(self, endpoints):
        return [{"endpoints": {"api": "https://api.githubcopilot.com"},
                 "login": "private-person", "token": "secret"},
                {"data": [{"id": "test-model", "supported_endpoints": endpoints,
                           "capabilities": {"supports": {"vision": True}}}]}]

    def test_discovery_does_not_infer_and_report_excludes_account_data(self):
        request = Mock(side_effect=self.responses(["/responses"]))
        report = contract.probe(request, "github.com", "secret")
        self.assertEqual(2, request.call_count)
        self.assertFalse(report["inference_verified"])
        self.assertEqual("test-model", report["models"][0]["id"])
        self.assertNotIn("secret", str(report))
        self.assertNotIn("private-person", str(report))

    def test_explicit_model_and_protocol_are_respected(self):
        cases = [("/chat/completions", {"choices": [{"message": {"content": "OK"}}]}, "messages"),
                 ("/responses", {"status": "completed", "output": [{"type": "message",
                    "content": [{"type": "output_text", "text": "OK"}]}]}, "input"),
                 ("/v1/messages", {"content": [{"type": "text", "text": "OK"}],
                    "stop_reason": "end_turn"}, "messages")]
        for endpoint, output, input_key in cases:
            with self.subTest(endpoint=endpoint):
                request = Mock(side_effect=self.responses([endpoint]) + [output])
                report = contract.probe(request, "github.com", "secret", "test-model", endpoint)
                self.assertTrue(report["inference_verified"])
                args = request.call_args.args
                self.assertEqual("https://api.githubcopilot.com" + endpoint, args[0])
                self.assertEqual("test-model", args[2]["model"])
                self.assertIn(input_key, args[2])

    def test_no_fallback_to_another_model_or_protocol(self):
        for model, endpoint in [("other-model", "/responses"), ("test-model", "/v1/messages")]:
            request = Mock(side_effect=self.responses(["/responses"]))
            with self.assertRaises(contract.ContractError):
                contract.probe(request, "github.com", "secret", model, endpoint)
            self.assertEqual(2, request.call_count)

    def test_http_success_without_text_does_not_pass(self):
        request = Mock(side_effect=self.responses(["/responses"]) + [{"output": []}])
        with self.assertRaises(contract.ContractError):
            contract.probe(request, "github.com", "secret", "test-model", "/responses")

    def test_device_polling_obeys_slow_down_and_does_not_announce_secrets(self):
        request = Mock(side_effect=[{"device_code": "secret-device", "user_code": "VISIBLE",
            "verification_uri": "https://github.com/login/device", "interval": 5, "expires_in": 100},
            {"error": "authorization_pending"}, {"error": "slow_down"}, {"access_token": "secret-token"}])
        announce, sleep = Mock(), Mock()
        token = contract.device_login(request, "github.com", "client", "", announce, sleep, lambda: 0)
        self.assertEqual("secret-token", token)
        self.assertEqual([5, 5, 10], [call.args[0] for call in sleep.call_args_list])
        self.assertNotIn("secret", str(announce.call_args_list))
        self.assertNotIn("scope", request.call_args_list[0].args[2])

    def test_device_denial_and_expiry_stop_polling(self):
        for error in ["access_denied", "expired_token"]:
            request = Mock(side_effect=[{"device_code": "secret-device", "user_code": "VISIBLE",
                "verification_uri": "https://acme.ghe.com/login/device", "interval": 5, "expires_in": 100},
                {"error": error}])
            with self.assertRaises(contract.ContractError):
                contract.device_login(request, "acme.ghe.com", "client", "", Mock(), Mock(), lambda: 0)
            self.assertEqual(2, request.call_count)

    def test_transport_does_not_print_upstream_secrets(self):
        error = HTTPError("https://api.githubcopilot.com/models", 401, "secret-token", {},
                          io.BytesIO(b'{"token":"secret-token"}'))
        opener = Mock()
        opener.open.side_effect = error
        with patch.object(contract, "build_opener", return_value=opener):
            with self.assertRaises(contract.ContractError) as caught:
                contract.request_json("https://api.githubcopilot.com/models", "secret-token")
        self.assertIn("401", str(caught.exception))
        self.assertNotIn("secret", str(caught.exception))

    def test_transport_bounds_response_and_refuses_redirects(self):
        opener = Mock()
        opener.open.return_value = io.BytesIO(b"x" * 9)
        with patch.object(contract, "build_opener", return_value=opener), patch.object(contract, "MAX_BYTES", 8):
            with self.assertRaises(contract.ContractError):
                contract.request_json("https://api.githubcopilot.com/models", "secret")
        self.assertIsNone(contract.NoRedirects().redirect_request(
            None, None, 302, "redirect", {}, "https://evil.test"))

    def test_incomplete_inference_arguments_do_not_read_credentials_or_send_requests(self):
        with patch.object(contract, "request_json") as request, patch("sys.stderr", new=io.StringIO()):
            code = contract.main(["--host", "github.com", "--token-env", "UNSET_PROBE_TOKEN",
                                  "--model", "test-model"])
        self.assertEqual(1, code)
        request.assert_not_called()

    def test_enterprise_endpoint_is_bound_to_selected_tenant(self):
        self.assertEqual("https://copilot.acme.ghe.com",
                         contract.inference_base("https://copilot.acme.ghe.com", "acme.ghe.com"))
        with self.assertRaises(contract.ContractError):
            contract.inference_base("https://copilot.other.ghe.com", "acme.ghe.com")

    def test_device_deadline_expires_without_an_extra_poll(self):
        request = Mock(return_value={"device_code": "secret-device", "user_code": "VISIBLE",
            "verification_uri": "https://github.com/login/device", "interval": 5, "expires_in": 10})
        clock = Mock(side_effect=[0, 0, 11])
        with self.assertRaises(contract.ContractError):
            contract.device_login(request, "github.com", "client", "", Mock(), Mock(), clock)
        self.assertEqual(1, request.call_count)

    def test_explicit_copilot_config_accepts_comments_and_checks_host(self):
        config = {"lastLoggedInUser": {"host": "https://github.com", "login": "test-user"},
                  "authTokens": {"https://github.com:test-user": {"token": "test-secret"}},
                  "ignored": "https://example.com/*not-a-comment*/"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            path.write_text("// Copilot config\n/* comment */\n" + json.dumps(config), encoding="utf-8")
            loader = getattr(contract, "load_copilot_config", None)
            self.assertIsNotNone(loader, "Explicit config input is not implemented")
            self.assertEqual("test-secret", loader(path, "github.com"))
            with self.assertRaises(contract.ContractError):
                loader(path, "acme.ghe.com")

    def test_missing_endpoint_metadata_requires_explicit_probe_override(self):
        responses = self.responses([])
        del responses[1]["data"][0]["supported_endpoints"]
        request = Mock(side_effect=responses)
        with self.assertRaises(contract.ContractError):
            contract.probe(request, "github.com", "secret", "test-model", "/chat/completions")
        self.assertEqual(2, request.call_count)
        request = Mock(side_effect=responses + [{"choices": [{"message": {"content": "OK"}}]}])
        report = contract.probe(request, "github.com", "secret", "test-model", "/chat/completions",
                                allow_unadvertised=True)
        self.assertTrue(report["inference_verified"])
        self.assertFalse(report["endpoint_advertised"])

    def test_probe_override_cannot_contradict_advertised_endpoints(self):
        request = Mock(side_effect=self.responses(["/responses"]))
        with self.assertRaises(contract.ContractError):
            contract.probe(request, "github.com", "secret", "test-model", "/chat/completions",
                           allow_unadvertised=True)
        self.assertEqual(2, request.call_count)


if __name__ == "__main__":
    unittest.main()
