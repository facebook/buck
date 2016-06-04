import unittest
import tempfile
import uuid
import os
import platform
import pkg_resources
from build_with_buck import *

XCODE_DWARF = "dwarf"
XCODE_DSYM = "dwarf-with-dsym"


class TestBuildWithBuck(unittest.TestCase):
    def run_with_data(self,
                      platform_name,
                      archs,
                      valid_archs,
                      debug_format,
                      repo_root,
                      buck_path,
                      flags,
                      target,
                      dwarf_flavor,
                      dsym_flavor):
        os.environ['PLATFORM_NAME'] = platform_name
        os.environ['ARCHS'] = archs
        os.environ['VALID_ARCHS'] = valid_archs
        os.environ['DEBUG_INFORMATION_FORMAT'] = debug_format
        return get_command(repo_root, buck_path, flags, target, dwarf_flavor, dsym_flavor)

    def test_generating_single_arch_dsym(self):
        if platform.system() != 'Darwin':
            # This script is expected to be used on OS X only
            return
        result = self.run_with_data("some_plat",
                                    "some_arch",
                                    "some_arch other_arch",
                                    XCODE_DSYM,
                                    "/repo/path",
                                    "/buck/path",
                                    "--flags",
                                    "//My:Target",
                                    "DWARF_FLAVOR",
                                    "DSYM_FLAVOR")
        self.assertEqual(result,
                         '/buck/path build --flags //My:Target#DSYM_FLAVOR,some_plat-some_arch')

    def test_generating_single_arch_dwarf(self):
        if platform.system() != 'Darwin':
            # This script is expected to be used on OS X only
            return
        result = self.run_with_data("some_plat",
                                    "some_arch",
                                    "some_arch other_arch",
                                    XCODE_DWARF,
                                    "/repo/path",
                                    "/buck/path",
                                    "--flags",
                                    "//My:Target",
                                    "DWARF_FLAVOR",
                                    "DSYM_FLAVOR")
        self.assertEqual(result,
                         '/buck/path build --flags //My:Target#DWARF_FLAVOR,some_plat-some_arch')

    def test_generating_single_arch_dwarf(self):
        if platform.system() != 'Darwin':
            # This script is expected to be used on OS X only
            return
        result = self.run_with_data("some_plat",
                                    "some_arch",
                                    "some_arch other_arch",
                                    XCODE_DWARF,
                                    "/repo/path",
                                    "/buck/path",
                                    "--flags",
                                    "//My:Target",
                                    "DWARF_FLAVOR",
                                    "DSYM_FLAVOR")
        self.assertEqual(result,
                         '/buck/path build --flags //My:Target#DWARF_FLAVOR,some_plat-some_arch')

    def test_generating_double_arch(self):
        if platform.system() != 'Darwin':
            # This script is expected to be used on OS X only
            return
        result = self.run_with_data("plat",
                                    "arch1 arch2",
                                    "arch2 arch1",
                                    XCODE_DWARF,
                                    "/repo/path",
                                    "/buck/path",
                                    "--flags",
                                    "//My:Target",
                                    "DWARF_FLAVOR",
                                    "DSYM_FLAVOR")
        self.assertEqual(result,
                         '/buck/path build --flags //My:Target#DWARF_FLAVOR,plat-arch1,plat-arch2')

    def test_generating_unsupported_arch(self):
        if platform.system() != 'Darwin':
            # This script is expected to be used on OS X only
            return
        with self.assertRaises(ValueError) as context:
            self.run_with_data("some_plat",
                               "----UNSUPPORTED_ARCH----",
                               "some_arch other_arch",
                               XCODE_DWARF,
                               "/repo/path",
                               "/buck/path",
                               "--flags",
                               "//My:Target",
                               "DWARF_FLAVOR",
                               "DSYM_FLAVOR")

    def test_generating_unsupported_debug_format(self):
        if platform.system() != 'Darwin':
            # This script is expected to be used on OS X only
            return
        with self.assertRaises(ValueError) as context:
            self.run_with_data("some_plat",
                               "some_arch",
                               "some_arch other_arch",
                               "------UNSUPPORTED-----",
                               "/repo/path",
                               "/buck/path",
                               "--flags",
                               "//My:Target",
                               "DWARF_FLAVOR",
                               "DSYM_FLAVOR")

if __name__ == '__main__':
    unittest.main()
