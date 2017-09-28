import unittest
import os
import subprocess
import tempfile


class DumpTest(unittest.TestCase):
    SCRIPT_DIR = os.path.dirname(os.path.realpath(__file__))

    def test_can_dump_exported_symbols_in_plain_text(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            build_file_path = os.path.join(temp_dir, 'BUCK')
            with open(build_file_path, 'w') as build_file:
                build_file.write('foo = "FOO"')
            output = subprocess.check_output(
                [os.path.join(self.SCRIPT_DIR, 'dump.py'), 'exported_symbols', build_file_path])

        self.assertEqual(b'foo', output.strip())

    def test_can_dump_exported_symbols_in_json(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            build_file_path = os.path.join(temp_dir, 'BUCK')
            with open(build_file_path, 'w') as build_file:
                build_file.write('foo = "FOO"')
            output = subprocess.check_output(
                [os.path.join(self.SCRIPT_DIR, 'dump.py'), '--json', 'exported_symbols',
                 build_file_path])

        self.assertEqual(b'["foo"]', output.strip())
