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
import os
import shutil
import unittest
import zipfile

from py.buck.zip import munger


def make_inputs(tempdir, *paths):
    for path in paths:
        fullpath = os.path.join(tempdir, path)
        dirname, filename = os.path.split(fullpath)
        if not os.path.exists(dirname):
            os.makedirs(dirname)
        else:
            assert os.path.isdir(dirname)
        with open(fullpath, "w") as f:
            f.write(filename)


def make_zip(tempdir, zip):
    shutil.make_archive(zip, "zip", tempdir)


def get_zip_contents(zip):
    with contextlib.closing(zipfile.ZipFile(zip)) as output:
        return [
            info.filename
            for info in output.infolist()
            if not info.filename.endswith("/")
        ]


class TestMunger(unittest.TestCase):
    def test_include_includes(self):
        with munger.tempdir() as temp_dir:
            output_file = os.path.join(temp_dir, "output")
            input_file = os.path.join(temp_dir, "input")

            workdir = os.path.join(temp_dir, "work")
            excluded = os.path.join("excluded")
            included = os.path.join(excluded, "included")

            make_inputs(workdir, os.path.join(excluded, "some_file"), included)
            make_zip(workdir, input_file)

            munger.process_jar(
                input_file + ".zip", output_file, ["excluded/included"], ["excluded"]
            )

            contents = get_zip_contents(output_file)
            self.assertEqual(
                len(contents),
                1,
                "Only one file should end up in the zip. Got [%s]."
                % (", ".join(contents)),
            )

            exists = False
            for path in contents:
                exists = exists or path == "excluded/included"
            self.assertTrue(exists, "Included file was included")

    def test_exclude_excludes(self):
        with munger.tempdir() as temp_dir:
            output_file = os.path.join(temp_dir, "output")
            input_file = os.path.join(temp_dir, "input")

            workdir = os.path.join(temp_dir, "work")
            included = os.path.join("included")
            excluded = os.path.join("excluded")
            make_inputs(workdir, included, excluded)
            make_zip(workdir, input_file)

            munger.process_jar(
                input_file + ".zip", output_file, ["other-include"], ["excluded"]
            )

            contents = get_zip_contents(output_file)
            self.assertEqual(
                len(contents),
                1,
                "Only one file should end up in the zip. Got [%s]."
                % (", ".join(contents)),
            )

            exists = False
            for path in contents:
                exists = exists or path == "included"
            self.assertTrue(exists, "Included file was included")


if __name__ == "__main__":
    unittest.main()
