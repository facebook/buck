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

"""Unit tests for dicts.bzl."""

load("//:lib.bzl", "asserts", "dicts", "unittest")

def _add_test(ctx):
    """Unit tests for dicts.add."""
    env = unittest.begin(ctx)

    # Test zero- and one-argument behavior.
    asserts.equals(env, {}, dicts.add())
    asserts.equals(env, {"a": 1}, dicts.add({"a": 1}))

    # Test simple two-argument behavior.
    asserts.equals(env, {"a": 1, "b": 2}, dicts.add({"a": 1}, {"b": 2}))

    # Test simple more-than-two-argument behavior.
    asserts.equals(
        env,
        {"a": 1, "b": 2, "c": 3, "d": 4},
        dicts.add({"a": 1}, {"b": 2}, {"c": 3}, {"d": 4}),
    )

    # Test same-key overriding.
    asserts.equals(env, {"a": 100}, dicts.add({"a": 1}, {"a": 100}))
    asserts.equals(env, {"a": 10}, dicts.add({"a": 1}, {"a": 100}, {"a": 10}))
    asserts.equals(
        env,
        {"a": 100, "b": 10},
        dicts.add({"a": 1}, {"a": 100}, {"b": 10}),
    )
    asserts.equals(env, {"a": 10}, dicts.add({"a": 1}, {}, {"a": 10}))
    asserts.equals(
        env,
        {"a": 10, "b": 5},
        dicts.add({"a": 1}, {"a": 10, "b": 5}),
    )

    # Test some other boundary cases.
    asserts.equals(env, {"a": 1}, dicts.add({"a": 1}, {}))

    # Since dictionaries are passed around by reference, make sure that the
    # result of dicts.add is always a *copy* by modifying it afterwards and
    # ensuring that the original argument doesn't also reflect the change. We do
    # this to protect against someone who might attempt to optimize the function
    # by returning the argument itself in the one-argument case.
    original = {"a": 1}
    result = dicts.add(original)
    result["a"] = 2
    asserts.equals(env, 1, original["a"])

    unittest.end(env)

add_test = unittest.make(_add_test)

def dicts_test_suite():
    """Creates the test targets and test suite for dicts.bzl tests."""
    unittest.suite(
        "dicts_tests",
        add_test,
    )
