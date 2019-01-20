#!/usr/bin/env python
# Copyright 2016-present Facebook, Inc.
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


# Copyright 2016-present Facebook, Inc.
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


import hashlib
import sys


def hash_files(files, verbose=False):
    """
    Hashes the content of given files using md5 algorithm in the sorted order
    """

    m = hashlib.md5()
    for file in sorted(files):
        if verbose:
            file_hasher = hashlib.md5()
        with open(file) as f:
            content = f.read()
            m.update(content)
            if verbose:
                file_hasher.update(content)
        if verbose:
            print file, file_hasher.hexdigest()
    return m.hexdigest()


if __name__ == '__main__':
    print hash_files(sys.argv[1:])
