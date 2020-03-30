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

"""Unit tests for selects.bzl."""

load("//:lib.bzl", "asserts", "selects", "unittest")

def _with_or_test(ctx):
    """Unit tests for with_or."""
    env = unittest.begin(ctx)

    # We actually test on with_or_dict because Skylark can't get the
    # dictionary from a select().

    # Test select()-compatible input syntax.
    input_dict = {"//conditions:default": ":d1", ":foo": ":d1"}
    asserts.equals(env, input_dict, selects.with_or_dict(input_dict))

    # Test OR syntax.
    or_dict = {(":foo", ":bar"): ":d1"}
    asserts.equals(
        env,
        {":bar": ":d1", ":foo": ":d1"},
        selects.with_or_dict(or_dict),
    )

    # Test mixed syntax.
    mixed_dict = {
        "//conditions:default": ":d3",
        (":bar", ":baz"): ":d2",
        ":foo": ":d1",
    }
    asserts.equals(
        env,
        {
            "//conditions:default": ":d3",
            ":bar": ":d2",
            ":baz": ":d2",
            ":foo": ":d1",
        },
        selects.with_or_dict(mixed_dict),
    )

    unittest.end(env)

with_or_test = unittest.make(_with_or_test)

def selects_test_suite():
    """Creates the test targets and test suite for selects.bzl tests."""
    unittest.suite(
        "selects_tests",
        with_or_test,
    )
