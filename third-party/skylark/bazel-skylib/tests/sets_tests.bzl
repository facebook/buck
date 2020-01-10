# Copyright 2017 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Unit tests for sets.bzl."""

load("//:lib.bzl", "asserts", "sets", "unittest")

def _is_equal_test(ctx):
    """Unit tests for sets.is_equal.

    Note that if this test fails, the results for the other `sets` tests will be
    inconclusive because they use `asserts.set_equals`, which in turn calls
    `sets.is_equal`.
    """
    env = unittest.begin(ctx)

    asserts.true(env, sets.is_equal([], []))
    asserts.false(env, sets.is_equal([], [1]))
    asserts.false(env, sets.is_equal([1], []))
    asserts.true(env, sets.is_equal([1], [1]))
    asserts.false(env, sets.is_equal([1], [1, 2]))
    asserts.false(env, sets.is_equal([1], [2]))
    asserts.false(env, sets.is_equal([1], depset([1, 2])))

    # Verify that the implementation is not using == on the sets directly.
    asserts.true(env, sets.is_equal(depset([1]), depset([1])))

    # If passing a list, verify that duplicate elements are ignored.
    asserts.true(env, sets.is_equal([1, 1], [1]))

    unittest.end(env)

is_equal_test = unittest.make(_is_equal_test)

def _is_subset_test(ctx):
    """Unit tests for sets.is_subset."""
    env = unittest.begin(ctx)

    asserts.true(env, sets.is_subset([], []))
    asserts.true(env, sets.is_subset([], [1]))
    asserts.false(env, sets.is_subset([1], []))
    asserts.true(env, sets.is_subset([1], [1]))
    asserts.true(env, sets.is_subset([1], [1, 2]))
    asserts.false(env, sets.is_subset([1], [2]))
    asserts.true(env, sets.is_subset([1], depset([1, 2])))

    # If passing a list, verify that duplicate elements are ignored.
    asserts.true(env, sets.is_subset([1, 1], [1, 2]))

    unittest.end(env)

is_subset_test = unittest.make(_is_subset_test)

def _disjoint_test(ctx):
    """Unit tests for sets.disjoint."""
    env = unittest.begin(ctx)

    asserts.true(env, sets.disjoint([], []))
    asserts.true(env, sets.disjoint([], [1]))
    asserts.true(env, sets.disjoint([1], []))
    asserts.false(env, sets.disjoint([1], [1]))
    asserts.false(env, sets.disjoint([1], [1, 2]))
    asserts.true(env, sets.disjoint([1], [2]))
    asserts.true(env, sets.disjoint([1], depset([2])))

    # If passing a list, verify that duplicate elements are ignored.
    asserts.false(env, sets.disjoint([1, 1], [1, 2]))

    unittest.end(env)

disjoint_test = unittest.make(_disjoint_test)

def _intersection_test(ctx):
    """Unit tests for sets.intersection."""
    env = unittest.begin(ctx)

    asserts.set_equals(env, [], sets.intersection([], []))
    asserts.set_equals(env, [], sets.intersection([], [1]))
    asserts.set_equals(env, [], sets.intersection([1], []))
    asserts.set_equals(env, [1], sets.intersection([1], [1]))
    asserts.set_equals(env, [1], sets.intersection([1], [1, 2]))
    asserts.set_equals(env, [], sets.intersection([1], [2]))
    asserts.set_equals(env, [1], sets.intersection([1], depset([1])))

    # If passing a list, verify that duplicate elements are ignored.
    asserts.set_equals(env, [1], sets.intersection([1, 1], [1, 2]))

    unittest.end(env)

intersection_test = unittest.make(_intersection_test)

def _union_test(ctx):
    """Unit tests for sets.union."""
    env = unittest.begin(ctx)

    asserts.set_equals(env, [], sets.union())
    asserts.set_equals(env, [1], sets.union([1]))
    asserts.set_equals(env, [], sets.union([], []))
    asserts.set_equals(env, [1], sets.union([], [1]))
    asserts.set_equals(env, [1], sets.union([1], []))
    asserts.set_equals(env, [1], sets.union([1], [1]))
    asserts.set_equals(env, [1, 2], sets.union([1], [1, 2]))
    asserts.set_equals(env, [1, 2], sets.union([1], [2]))
    asserts.set_equals(env, [1], sets.union([1], depset([1])))

    # If passing a list, verify that duplicate elements are ignored.
    asserts.set_equals(env, [1, 2], sets.union([1, 1], [1, 2]))

    unittest.end(env)

union_test = unittest.make(_union_test)

def _difference_test(ctx):
    """Unit tests for sets.difference."""
    env = unittest.begin(ctx)

    asserts.set_equals(env, [], sets.difference([], []))
    asserts.set_equals(env, [], sets.difference([], [1]))
    asserts.set_equals(env, [1], sets.difference([1], []))
    asserts.set_equals(env, [], sets.difference([1], [1]))
    asserts.set_equals(env, [], sets.difference([1], [1, 2]))
    asserts.set_equals(env, [1], sets.difference([1], [2]))
    asserts.set_equals(env, [], sets.difference([1], depset([1])))

    # If passing a list, verify that duplicate elements are ignored.
    asserts.set_equals(env, [2], sets.difference([1, 2], [1, 1]))

    unittest.end(env)

difference_test = unittest.make(_difference_test)

def sets_test_suite():
    """Creates the test targets and test suite for sets.bzl tests."""
    unittest.suite(
        "sets_tests",
        disjoint_test,
        intersection_test,
        is_equal_test,
        is_subset_test,
        difference_test,
        union_test,
    )
