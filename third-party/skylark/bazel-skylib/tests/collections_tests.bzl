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

"""Unit tests for collections.bzl."""

load("//:lib.bzl", "asserts", "collections", "unittest")

def _after_each_test(ctx):
    """Unit tests for collections.after_each."""
    env = unittest.begin(ctx)

    asserts.equals(env, [], collections.after_each("1", []))
    asserts.equals(env, ["a", "1"], collections.after_each("1", ["a"]))
    asserts.equals(
        env,
        ["a", "1", "b", "1"],
        collections.after_each("1", ["a", "b"]),
    )

    # We don't care what type the separator is, we just put it there; so None
    # should be just as valid as anything else.
    asserts.equals(
        env,
        ["a", None, "b", None],
        collections.after_each(None, ["a", "b"]),
    )

    unittest.end(env)

after_each_test = unittest.make(_after_each_test)

def _before_each_test(ctx):
    """Unit tests for collections.before_each."""
    env = unittest.begin(ctx)

    asserts.equals(env, [], collections.before_each("1", []))
    asserts.equals(env, ["1", "a"], collections.before_each("1", ["a"]))
    asserts.equals(
        env,
        ["1", "a", "1", "b"],
        collections.before_each("1", ["a", "b"]),
    )

    # We don't care what type the separator is, we just put it there; so None
    # should be just as valid as anything else.
    asserts.equals(
        env,
        [None, "a", None, "b"],
        collections.before_each(None, ["a", "b"]),
    )

    unittest.end(env)

before_each_test = unittest.make(_before_each_test)

def _uniq_test(ctx):
    env = unittest.begin(ctx)
    asserts.equals(env, collections.uniq([0, 1, 2, 3]), [0, 1, 2, 3])
    asserts.equals(env, collections.uniq([]), [])
    asserts.equals(env, collections.uniq([1, 1, 1, 1, 1]), [1])
    asserts.equals(
        env,
        collections.uniq([
            True,
            5,
            "foo",
            5,
            False,
            struct(a = 1),
            True,
            struct(b = 2),
            "bar",
            (1,),
            "foo",
            struct(a = 1),
            (1,),
        ]),
        [
            True,
            5,
            "foo",
            False,
            struct(a = 1),
            struct(b = 2),
            "bar",
            (1,),
        ],
    )

    unittest.end(env)

uniq_test = unittest.make(_uniq_test)

def collections_test_suite():
    """Creates the test targets and test suite for collections.bzl tests."""
    unittest.suite(
        "collections_tests",
        after_each_test,
        before_each_test,
        uniq_test,
    )
