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

"""Unit tests for structs.bzl."""

load("//:lib.bzl", "asserts", "structs", "unittest")

def _add_test(ctx):
    """Unit tests for dicts.add."""
    env = unittest.begin(ctx)

    # Test zero- and one-argument behavior.
    asserts.equals(env, {}, structs.to_dict(struct()))
    asserts.equals(env, {"a": 1}, structs.to_dict(struct(a = 1)))

    # Test simple two-argument behavior.
    asserts.equals(env, {"a": 1, "b": 2}, structs.to_dict(struct(a = 1, b = 2)))

    # Test simple more-than-two-argument behavior.
    asserts.equals(
        env,
        {"a": 1, "b": 2, "c": 3, "d": 4},
        structs.to_dict(struct(a = 1, b = 2, c = 3, d = 4)),
    )

    # Test transformation is not applied transitively.
    asserts.equals(
        env,
        {"a": 1, "b": struct(bb = 1)},
        structs.to_dict(struct(a = 1, b = struct(bb = 1))),
    )

    unittest.end(env)

add_test = unittest.make(_add_test)

def structs_test_suite():
    """Creates the test targets and test suite for structs.bzl tests."""
    unittest.suite(
        "structs_tests",
        add_test,
    )
