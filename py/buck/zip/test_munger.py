# Copyright 2015-present Facebook, Inc.
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

import contextlib
import unittest
import os
import zipfile

from py.buck.zip import munger


class TestMunger(unittest.TestCase):
    def test_include_includes(self):
        with munger.tempdir() as temp_dir:
            output_file = os.path.join(temp_dir, 'output')
            input_file = os.path.join(temp_dir, 'input')
            included = os.path.join(temp_dir, 'included')
            with open(included, 'w') as f:
                f.write('some content')
            excluded = os.path.join(temp_dir, 'excluded')
            with open(excluded, 'w') as f:
                f.write('some content')
            with contextlib.closing(zipfile.ZipFile(input_file, 'w')) as input:
                input.write(included, os.path.relpath(included, temp_dir))
                input.write(excluded, os.path.relpath(excluded, temp_dir))

            munger.process_jar(
                input_file,
                output_file,
                ['included'],
                [])

            with contextlib.closing(zipfile.ZipFile(output_file)) as output:
                self.assertEqual(len(output.infolist()), 1, 'Only one file ended up in the zip')
                exists = False
                for info in output.infolist():
                    exists = exists or info.filename == 'included'
                self.assertTrue(exists, 'Included file was included')

    def test_exclude_excludes(self):
        with munger.tempdir() as temp_dir:
            output_file = os.path.join(temp_dir, 'output')
            input_file = os.path.join(temp_dir, 'input')
            included = os.path.join(temp_dir, 'included')
            with open(included, 'w') as f:
                f.write('some content')
            excluded = os.path.join(temp_dir, 'excluded')
            with open(excluded, 'w') as f:
                f.write('some content')
            with contextlib.closing(zipfile.ZipFile(input_file, 'w')) as input:
                input.write(included, os.path.relpath(included, temp_dir))
                input.write(excluded, os.path.relpath(excluded, temp_dir))

            munger.process_jar(
                input_file,
                output_file,
                [],
                ['excluded'])

            with contextlib.closing(zipfile.ZipFile(output_file)) as output:
                self.assertEqual(len(output.infolist()), 1, 'Only one file ended up in the zip')
                exists = False
                for info in output.infolist():
                    exists = exists or info.filename == 'included'
                self.assertTrue(exists, 'Included file was included')

if __name__ == '__main__':
    unittest.main()
