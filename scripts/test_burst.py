"""Deterministic HTTP retry tests; no network, real waits, or credentials."""
import io
import json
import os
import unittest
import urllib.error
from contextlib import redirect_stdout
from unittest.mock import patch

with patch.dict(os.environ, {
    "BASE_URL": "http://localhost:8080", "BASE_URLS": "http://localhost:8080",
    "BURST_MAX_RETRIES": "0", "BURST_TOKENS_FILE": "", "WALLET_AUTH_SECRET": "",
}):
    import burst


def response(status, body, correlation=None, retry_after="1"):
    return urllib.error.HTTPError(
        "http://localhost:8080/transfers", status, "test response",
        {"X-Correlation-Id": correlation or f"burst-{burst.RUN}", "Retry-After": retry_after},
        io.BytesIO(json.dumps(body).encode()),
    )


class BurstRetryTests(unittest.TestCase):
    def setUp(self):
        burst.LATENCIES.clear()
        burst.OPERATION_LATENCIES.clear()
        burst.HTTP_STATUSES.clear()
        self.retries = patch.object(burst, "MAX_RETRIES", 3)
        self.retries.start()
        self.addCleanup(self.retries.stop)
        self.wait = patch.object(burst.time, "sleep")
        self.sleep = self.wait.start()
        self.addCleanup(self.wait.stop)
        self.output = redirect_stdout(io.StringIO())
        self.output.__enter__()
        self.addCleanup(self.output.__exit__, None, None, None)

    def call(self):
        return burst.request("POST", "/transfers", "sender", {"idempotency_key": "same-key", "amount_paise": 100})

    def test_retry_reuses_request_and_counts_failure(self):
        responses = [response(503, {"code": "temporarily_unavailable"}, retry_after="2"),
                     response(200, {"status": "SUCCEEDED"})]
        with patch.object(burst.urllib.request, "urlopen", side_effect=responses) as send:
            self.assertEqual(self.call(), {"status": "SUCCEEDED"})
        first, second = [call.args[0] for call in send.call_args_list]
        self.assertIs(first, second)
        self.assertEqual(json.loads(first.data)["idempotency_key"], "same-key")
        self.assertIn("Authorization", first.headers)
        self.assertGreaterEqual(self.sleep.call_args.args[0], 2)
        self.assertEqual(dict(burst.HTTP_STATUSES), {503: 1, 200: 1})
        self.assertEqual(len(burst.OPERATION_LATENCIES), 1)
        self.assertGreaterEqual(burst.OPERATION_LATENCIES[0], sum(burst.LATENCIES))

    def test_strict_mode_fails_without_retry(self):
        with patch.object(burst, "MAX_RETRIES", 0), patch.object(
            burst.urllib.request, "urlopen", side_effect=[response(503, {"code": "temporarily_unavailable"})]
        ) as send:
            with self.assertRaises(AssertionError):
                self.call()
        self.assertEqual(send.call_count, 1)
        self.sleep.assert_not_called()

    def test_retries_stop_at_bound(self):
        with patch.object(burst.urllib.request, "urlopen", side_effect=[
            response(503, {"code": "temporarily_unavailable"}) for _ in range(4)
        ]) as send:
            with self.assertRaises(AssertionError):
                self.call()
        self.assertEqual(send.call_count, 4)
        self.assertEqual(self.sleep.call_count, 3)

    def test_other_errors_are_not_retried(self):
        for status in (400, 401, 409, 500):
            with self.subTest(status=status), patch.object(burst.urllib.request, "urlopen", side_effect=[
                response(status, {"code": "other_error"})
            ]) as send:
                with self.assertRaises(AssertionError):
                    self.call()
                self.assertEqual(send.call_count, 1)
        self.sleep.assert_not_called()

    def test_unrecognized_503_is_not_retried(self):
        with patch.object(burst.urllib.request, "urlopen", side_effect=[response(503, {"code": "other_error"})]):
            with self.assertRaises(AssertionError):
                self.call()
        self.sleep.assert_not_called()

    def test_bad_correlation_is_not_retried(self):
        with patch.object(burst.urllib.request, "urlopen", side_effect=[
            response(503, {"code": "temporarily_unavailable"}, correlation="wrong")
        ]):
            with self.assertRaisesRegex(AssertionError, "Correlation"):
                self.call()
        self.sleep.assert_not_called()

    def test_unbounded_retry_after_is_rejected(self):
        with patch.object(burst.urllib.request, "urlopen", side_effect=[
            response(503, {"code": "temporarily_unavailable"}, retry_after="3600")
        ]):
            with self.assertRaisesRegex(AssertionError, "Retry-After"):
                self.call()
        self.sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()