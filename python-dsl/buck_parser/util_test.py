# Copyright 2018-present Facebook, Inc.
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

from __future__ import absolute_import, division, print_function, with_statement

import unittest

from . import util


class UtilTest(unittest.TestCase):
    def test_is_in_dir(self):
        self.assertTrue(util.is_in_dir("foo/bar.py", "foo"))
        self.assertTrue(util.is_in_dir("foo/bar.py", "foo/"))
        self.assertTrue(util.is_in_dir("/foo/bar.py", "/"))
        self.assertFalse(util.is_in_dir("foo.py", "foo"))
        self.assertFalse(util.is_in_dir("foo/bar.py", "foo/bar"))
        self.assertFalse(util.is_in_dir("foo/bars", "foo/bar"))


if __name__ == "__main__":
    unittest.main()
