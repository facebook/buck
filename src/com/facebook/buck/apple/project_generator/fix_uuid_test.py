import unittest
import tempfile
import uuid
import os
import platform
import pkg_resources
from distutils.dir_util import copy_tree
from fix_uuid import *


class TestUUIDFix(unittest.TestCase):
    test_app_folder = 'uuid_test_app'
    binary_name = 'UUIDTest'

    def prepare_test_bundle(self):
        stream = pkg_resources.resource_stream(__name__, "uuid_test_app/UUIDTest")
        with open(os.path.join(self.tmp_dir, TestUUIDFix.binary_name), 'w') as f:
            f.write(stream.read())
        dwarf_path_in_tmp = get_DWARF_file_path(
            os.path.join(self.tmp_dir, TestUUIDFix.binary_name + '.dSYM'), TestUUIDFix.binary_name)
        # we need to create path up to the parent of dwarf file:
        os.makedirs(os.path.abspath(os.path.join(dwarf_path_in_tmp, os.pardir)))
        dward_stream_path = ("uuid_test_app/UUIDTest.dSYM/Contents/Resources/DWARF/" +
                             TestUUIDFix.binary_name)
        stream = pkg_resources.resource_stream(__name__, dward_stream_path)
        with open(dwarf_path_in_tmp, 'w') as f:
            f.write(stream.read())

    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp()
        pass

    def test_getting_path_to_DWARF(self):
        if platform.system() != 'Darwin':
            # This script is expected to be used on OS X only
            return
        self.assertEqual(get_DWARF_file_path('/PathTo.dSYM', 'BinaryName'),
                         '/PathTo.dSYM/Contents/Resources/DWARF/BinaryName')

    def test_getting_UUIDs(self):
        if platform.system() != 'Darwin':
            # This script is expected to be used on OS X only
            return
        self.prepare_test_bundle()
        binary_path = os.path.join(self.tmp_dir, TestUUIDFix.binary_name)
        UUIDs = set(get_UUIDs_for_binary_at_path(binary_path, False))
        expected_UUIDs = set([uuid.UUID("6B537A7B-854C-3A1A-A60D-3C6BD0B0F28F"),
                              uuid.UUID("4CC05DC7-EC03-3E92-BC95-3981D706E727")])
        self.assertEqual(UUIDs, expected_UUIDs)

    def test_replacing_UUIDs(self):
        if platform.system() != 'Darwin':
            # This script is expected to be used on OS X only
            return
        self.prepare_test_bundle()
        binary_path = os.path.join(self.tmp_dir, TestUUIDFix.binary_name)
        dSYM_path = os.path.join(self.tmp_dir, TestUUIDFix.binary_name + '.dSYM')
        old_UUIDs = get_UUIDs_for_binary_at_path(binary_path, False)
        new_UUIDs = get_new_UUIDs_for_old_UUIDs(old_UUIDs, False)
        replace_UUIDs(self.tmp_dir, dSYM_path, TestUUIDFix.binary_name, False)
        actual_UUIDs = get_UUIDs_for_binary_at_path(binary_path, False)
        self.assertEqual(set(new_UUIDs), set(actual_UUIDs))


if __name__ == '__main__':
    unittest.main()
