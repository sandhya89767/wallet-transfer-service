import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

import submission_check as check


class PersistenceCheckTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.folder = Path(temp.name)
        self.state = {'base': 'https://example.invalid', 'correlation_id': 'test',
                      'tokens': {'sender': 'private-a', 'recipient': 'private-b'},
                      'body': {'from': 'a', 'to': 'b', 'amount_paise': 7500, 'idempotency_key': 'same'},
                      'transfer': {'id': 't', 'status': 'SUCCEEDED'}, 'balances': [92500, 27500],
                      'process_start_before': 100}
        check.private_write(self.folder / 'persistence-state.json', self.state)

    def test_requires_new_process(self):
        with patch.object(check, 'process_start', return_value=100), patch.object(
                check, 'request', return_value={'status': 'UP'}):
            with self.assertRaisesRegex(RuntimeError, 'No newer process'):
                check.after(self.folder)

    def test_replay_and_balance_checks_exclude_tokens_from_evidence(self):
        results = [{'status': 'UP'}, self.state['transfer'], {'balance_paise': 92500},
                   {'balance_paise': 27500}, self.state['transfer'],
                   {'balance_paise': 92500}, {'balance_paise': 27500}]
        with patch.object(check, 'process_start', return_value=200), patch.object(
                check, 'request', side_effect=results) as request, redirect_stdout(io.StringIO()) as output:
            check.after(self.folder)
        self.assertEqual(request.call_args_list[4].args[-1], self.state['body'])
        evidence = (self.folder / 'after-public-evidence.json').read_text()
        self.assertEqual(json.loads(evidence)['result'], 'PASS')
        self.assertNotIn('private-a', evidence + output.getvalue())
        self.assertNotIn('private-b', evidence + output.getvalue())
        self.assertEqual((self.folder / 'after-public-evidence.json').stat().st_mode & 0o777, 0o600)

    def test_rejects_second_debit(self):
        results = [{'status': 'UP'}, self.state['transfer'], {'balance_paise': 92500},
                   {'balance_paise': 27500}, self.state['transfer'],
                   {'balance_paise': 85000}, {'balance_paise': 35000}]
        with patch.object(check, 'process_start', return_value=200), patch.object(
                check, 'request', side_effect=results):
            with self.assertRaisesRegex(RuntimeError, 'Balances changed'):
                check.after(self.folder)
        self.assertFalse((self.folder / 'after-public-evidence.json').exists())

    def test_does_not_overwrite_private_files(self):
        with self.assertRaises(FileExistsError):
            check.private_write(self.folder / 'persistence-state.json', {})


if __name__ == '__main__':
    unittest.main()