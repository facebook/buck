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

    def test_can_dump_export_map_in_plain_text(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            build_file_path = os.path.join(temp_dir, 'BUCK')
            with open(build_file_path, 'w') as build_file:
                build_file.write('include_defs("cell//DEFS")')
            with open(os.path.join(temp_dir, 'DEFS'), 'w') as defs_file:
                defs_file.write('foo = "FOO"')
            output = subprocess.check_output(
                [os.path.join(self.SCRIPT_DIR, 'dump.py'), '--cell_root', 'cell=' + temp_dir,
                 'export_map', build_file_path])

        self.assertEqual(b"cell//DEFS:\n  foo", output.strip())

    def test_can_dump_export_map_in_json(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            build_file_path = os.path.join(temp_dir, 'BUCK')
            with open(build_file_path, 'w') as build_file:
                build_file.write('include_defs("cell//DEFS")')
            with open(os.path.join(temp_dir, 'DEFS'), 'w') as defs_file:
                defs_file.write('foo = "FOO"')
            output = subprocess.check_output(
                [os.path.join(self.SCRIPT_DIR, 'dump.py'), '--json', '--cell_root',
                 'cell=' + temp_dir, 'export_map', build_file_path])

        self.assertEqual(b'{"cell//DEFS": ["foo"]}', output.strip())
