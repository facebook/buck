#!/usr/bin/env python
#
# Copyright 2014-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.
#
# Unit tests for compile_asset_catalog.python

from compile_asset_catalogs import major_version_from_version_string
from compile_asset_catalogs import minor_version_from_version_string
from compile_asset_catalogs import catalog_name_from_path
from compile_asset_catalogs import actool_cmds
from compile_asset_catalogs import transform_actool_output_line
from compile_asset_catalogs import transform_actool_output

import StringIO
import unittest


class TestCompileAssetCatalogs(unittest.TestCase):

    def test_parses_major_version_correctly(self):
        self.assertEqual(major_version_from_version_string('10.9'), 10)
        self.assertEqual(major_version_from_version_string('7.0'), 7)
        self.assertEqual(major_version_from_version_string('6.1.3'), 6)

    def test_parses_minor_version_correctly(self):
        self.assertEqual(minor_version_from_version_string('10.9'), 9)
        self.assertEqual(minor_version_from_version_string('6.1.3'), 1)
        self.assertRaises(ValueError, minor_version_from_version_string, ('1'))

    def test_parses_catalog_name_correctly(self):
        self.assertEqual(
            catalog_name_from_path('/AssetCatalog.xcassets'),
            'AssetCatalog')
        self.assertEqual(catalog_name_from_path('/a/b/c/d.xcassets'), 'd')

    def test_transforms_actool_warning_to_error(self):
        line = transform_actool_output_line(
            '/path/to/AssetCatalog.xcassets/some.imageset/some.png: warning: '
            'something')
        self.assertTrue(line.startswith('error:'))

    def test_does_not_produce_error_for_informational_actool_output(self):
        line = transform_actool_output_line(
            '/path/to/AssetCatalog.xcassets/some.imageset/some.png: cool '
            'image dude')
        self.assertFalse(line.startswith('error:'))

    def test_returns_false_when_warnings_exist(self):
        sample = StringIO.StringIO("""
    asset compiler starting
    /path/to/AssetCatalog.xcassets/some.imageset/some.png: warning: something
    done
    """)
        self.assertFalse(transform_actool_output(sample))
        sample.close()

    def test_returns_true_when_no_errors_encountered(self):
        sample = StringIO.StringIO("""
    asset compiler starting
    done
    """)
        self.assertTrue(transform_actool_output(sample))
        sample.close()

    def test_splits_into_bundles_when_told_prior_to_ios7(self):
        catalogs = ['/A.xcassets', '/B.xcassets']
        pairs = actool_cmds(
            '6.0',
            'iphoneos',
            ['iphone'],
            '/output',
            catalogs,
            True)
        self.assertTrue(len(pairs) == 2)
        (c, d) = pairs[0]
        s = ' '.join(c)
        self.assertTrue('--compile /output/A.bundle /A.xcassets' in s)
        (c, d) = pairs[1]
        s = ' '.join(c)
        self.assertTrue('--compile /output/B.bundle /B.xcassets' in s)

    def test_splits_into_bundles_when_told_post_ios7(self):
        catalogs = ['/A.xcassets', '/B.xcassets']
        pairs = actool_cmds(
            '7.0',
            'iphoneos',
            ['iphone'],
            '/output',
            catalogs,
            True)
        self.assertTrue(len(pairs) == 2)
        (c, d) = pairs[0]
        s = ' '.join(c)
        self.assertTrue('--compile /output/A.bundle /A.xcassets' in s)
        (c, d) = pairs[1]
        s = ' '.join(c)
        self.assertTrue('--compile /output/B.bundle /B.xcassets' in s)

    def test_splits_into_bundles_when_told_prior_to_osx10_9(self):
        catalogs = ['/A.xcassets', '/B.xcassets']
        pairs = actool_cmds(
            '10.8',
            'macosx',
            ['mac'],
            '/output',
            catalogs,
            True)
        self.assertTrue(len(pairs) == 2)
        (c, d) = pairs[0]
        s = ' '.join(c)
        self.assertTrue('--compile /output/A.bundle /A.xcassets' in s)
        (c, d) = pairs[1]
        s = ' '.join(c)
        self.assertTrue('--compile /output/B.bundle /B.xcassets' in s)

    def test_splits_into_bundles_when_told_post_osx10_9(self):
        catalogs = ['/A.xcassets', '/B.xcassets']
        pairs = actool_cmds(
            '10.9',
            'macosx',
            ['mac'],
            '/output',
            catalogs,
            True)
        self.assertTrue(len(pairs) == 2)
        (c, d) = pairs[0]
        s = ' '.join(c)
        self.assertTrue('--compile /output/A.bundle /A.xcassets' in s)
        (c, d) = pairs[1]
        s = ' '.join(c)
        self.assertTrue('--compile /output/B.bundle /B.xcassets' in s)

    def test_generates_one_actool_cmd_with_root_output_when_split_bundles_is_false(self):
        catalogs = ['/A.xcassets', '/B.xcassets']
        self.assertOneActoolCommandWithRootOutput(actool_cmds(
            '10.9', 'macosx', ['mac'], '/output', catalogs, False))
        self.assertOneActoolCommandWithRootOutput(actool_cmds(
            '10.8', 'macosx', ['mac'], '/output', catalogs, False))
        self.assertOneActoolCommandWithRootOutput(actool_cmds(
            '6.0', 'iphoneos', ['iphone'], '/output', catalogs, False))
        self.assertOneActoolCommandWithRootOutput(actool_cmds(
            '7.0', 'iphoneos', ['iphone'], '/output', catalogs, False))

    def assertOneActoolCommandWithRootOutput(self, pairs):
        self.assertTrue(len(pairs) == 1)
        (c, d) = pairs[0]
        s = ' '.join(c)
        self.assertTrue('--compile /output /A.xcassets /B.xcassets' in s)

    def test_specifies_minimum_deployment_target_in_actool_cmd(self):
        pairs = actool_cmds(
            '6.0.1',
            'iphoneos',
            ['iphone'],
            '/output',
            ['/A.xcassets'],
            False)
        self.assertTrue(len(pairs) == 1)
        (c, d) = pairs[0]
        self.assertTrue('--minimum-deployment-target 6.0.1' in ' '.join(c))

    def test_specifies_forced_minimum_deployment_target_in_actool_cmd_when_split_bundles_is_specified(self):
        pairs = actool_cmds(
            '7.0',
            'iphoneos',
            ['iphone'],
            '/output',
            ['/A.xcassets'],
            True)
        self.assertTrue(len(pairs) == 1)
        (c, d) = pairs[0]
        self.assertTrue('--minimum-deployment-target 6.0' in ' '.join(c))

    def test_specifies_platform_in_actool_cmd(self):
        pairs = actool_cmds(
            '7.0',
            'iphoneos',
            ['iphone'],
            '/output',
            ['/A.xcassets'],
            False)
        self.assertTrue(len(pairs) == 1)
        (c, d) = pairs[0]
        self.assertTrue('--platform iphoneos' in ' '.join(c))

    def test_specifies_devices_in_actool_cmd(self):
        pairs = actool_cmds(
            '7.0',
            'iphoneos',
            ['iphone', 'ipad'],
            '/output',
            ['/A.xcassets'],
            False)
        self.assertTrue(len(pairs) == 1)
        (c, d) = pairs[0]
        s = ' '.join(c)
        self.assertTrue('--target-device iphone' in s)
        self.assertTrue('--target-device ipad' in s)

if __name__ == '__main__':
    unittest.main()
