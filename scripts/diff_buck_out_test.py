# Copyright (c) Facebook, Inc. and its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import pkg_resources
from diff_buck_out import compare_dirs


def test_compare_dirs_nested_different():
    testdir1 = pkg_resources.resource_filename(
        "scripts.diff_buck_out_test", "testdata/nested_test_diff/nested_dir1/"
    )
    testdir2 = pkg_resources.resource_filename(
        "scripts.diff_buck_out_test", "testdata/nested_test_diff/nested_dir2/"
    )
    matching_paths, mismatching_paths, hashes_by_path = compare_dirs(
        testdir1, testdir2, "testing"
    )
    assert matching_paths == 0
    assert mismatching_paths == 3


def test_compare_dirs_nested_same():
    testdir1 = pkg_resources.resource_filename(
        "scripts.diff_buck_out_test", "testdata/nested_test_same/nested_dir3/"
    )
    testdir2 = pkg_resources.resource_filename(
        "scripts.diff_buck_out_test", "testdata/nested_test_same/nested_dir4/"
    )
    matching_paths, mismatching_paths, hashes_by_path = compare_dirs(
        testdir1, testdir2, "testing"
    )
    assert matching_paths == 1
    assert mismatching_paths == 0


def test_compare_dirs_same():
    testdir1 = pkg_resources.resource_filename(
        "scripts.diff_buck_out_test", "testdata/test_same/dir1/"
    )
    testdir2 = pkg_resources.resource_filename(
        "scripts.diff_buck_out_test", "testdata/test_same/dir2/"
    )
    matching_paths, mismatching_paths, hashes_by_path = compare_dirs(
        testdir1, testdir2, "testing"
    )
    assert matching_paths == 1
    assert mismatching_paths == 0


def test_compare_dirs_diff():
    testdir1 = pkg_resources.resource_filename(
        "scripts.diff_buck_out_test", "testdata/test_diff/dir1/"
    )
    testdir2 = pkg_resources.resource_filename(
        "scripts.diff_buck_out_test", "testdata/test_diff/dir2/"
    )
    matching_paths, mismatching_paths, hashes_by_path = compare_dirs(
        testdir1, testdir2, "testing"
    )
    assert matching_paths == 0
    assert mismatching_paths == 2
