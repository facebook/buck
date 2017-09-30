import unittest
import os
import subprocess
import tempfile

from typing import Any


class DumpTest(unittest.TestCase):
    SCRIPT_DIR = os.path.dirname(os.path.realpath(__file__))

    def _dump(self, *args: Any) -> str:
        return subprocess.check_output(
            [os.path.join(self.SCRIPT_DIR, 'dump.py')] + list(args)).decode()

    def test_can_dump_exported_symbols_in_plain_text(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            build_file_path = os.path.join(temp_dir, 'BUCK')
            with open(build_file_path, 'w') as build_file:
                build_file.write('foo = "FOO"')
            output = self._dump('exported_symbols', build_file_path)

        self.assertEqual('foo', output.strip())

    def test_can_dump_exported_symbols_in_json(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            build_file_path = os.path.join(temp_dir, 'BUCK')
            with open(build_file_path, 'w') as build_file:
                build_file.write('foo = "FOO"')
            output = self._dump('--json', 'exported_symbols', build_file_path)

        self.assertEqual('["foo"]', output.strip())

    def test_can_dump_export_map_in_plain_text(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            build_file_path = os.path.join(temp_dir, 'BUCK')
            with open(build_file_path, 'w') as build_file:
                build_file.write('include_defs("cell//DEFS")')
            with open(os.path.join(temp_dir, 'DEFS'), 'w') as defs_file:
                defs_file.write('foo = "FOO"')
            output = self._dump('--cell_root', 'cell=' + temp_dir, 'export_map', build_file_path)

        self.assertEqual("cell//DEFS:\n  foo", output.strip())

    def test_can_dump_export_map_in_json(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            build_file_path = os.path.join(temp_dir, 'BUCK')
            with open(build_file_path, 'w') as build_file:
                build_file.write('include_defs("cell//DEFS")')
            with open(os.path.join(temp_dir, 'DEFS'), 'w') as defs_file:
                defs_file.write('foo = "FOO"')
            output = self._dump('--json', '--cell_root',
                                'cell=' + temp_dir, 'export_map', build_file_path)

        self.assertEqual('{"cell//DEFS": ["foo"]}', output.strip())

    def test_can_dump_export_map_as_load_functions_in_plain_text(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            build_file_path = os.path.join(temp_dir, 'BUCK')
            with open(build_file_path, 'w') as build_file:
                build_file.write('include_defs("cell//DEFS")')
            with open(os.path.join(temp_dir, 'DEFS'), 'w') as defs_file:
                defs_file.write('foo = "FOO"')
            output = self._dump('--cell_root', 'cell=' + temp_dir,
                                'export_map', '--print_as_load_functions', build_file_path)

        self.assertEqual('load("@cell//:DEFS", "foo")', output.strip())

    def test_can_dump_export_map_as_load_functions_in_json(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            build_file_path = os.path.join(temp_dir, 'BUCK')
            with open(build_file_path, 'w') as build_file:
                build_file.write('include_defs("cell//DEFS")')
            with open(os.path.join(temp_dir, 'DEFS'), 'w') as defs_file:
                defs_file.write('foo = "FOO"')
            output = self._dump('--json', '--cell_root',
                                'cell=' + temp_dir, 'export_map', '--print_as_load_functions',
                                build_file_path)

        self.assertEqual('["load(\\"@cell//:DEFS\\", \\"foo\\")"]', output.strip())
