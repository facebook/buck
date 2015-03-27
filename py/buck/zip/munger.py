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
import optparse
import shutil
import sys
import tempfile
import zipfile


def main():
    parser = optparse.OptionParser()
    parser.add_option('--input')
    parser.add_option('--output')
    parser.add_option(
        '--include-path',
        action='append',
        default=[])
    parser.add_option(
        '--exclude-path',
        action='append',
        default=[])
    options, _ = parser.parse_args()
    process_jar(options.input, options.output, options.include_path, options.exclude_path)


def process_jar(infile, outfile, include_paths, exclude_paths):
    with tempdir() as temp_dir:
        # First extract all the files we need from the jar.
        with contextlib.closing(zipfile.ZipFile(infile)) as input:
            for info in input.infolist():
                include = len(include_paths) == 0
                for path in include_paths:
                    include = include or info.filename.startswith(path)
                exclude = False
                for path in exclude_paths:
                    exclude = exclude or info.filename.startswith(path)
                if include and not exclude:
                    input.extract(info, temp_dir)

        # Now we can package the files we extracted into our specified destination.
        with contextlib.closing(zipfile.ZipFile(outfile, 'w')) as output:
            for root, _, files in os.walk(temp_dir):
                for file in files:
                    file = os.path.join(root, file)
                    output.write(file, os.path.relpath(file, temp_dir))


@contextlib.contextmanager
def tempdir():
    path = tempfile.mkdtemp()
    try:
        yield path
    finally:
        try:
            shutil.rmtree(path)
        except IOError:
            sys.stderr.write('Failed to remove {0}'.format(path))


if __name__ == '__main__':
    main()
