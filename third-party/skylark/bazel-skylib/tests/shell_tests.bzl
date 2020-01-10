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

"""Unit tests for shell.bzl."""

load("//:lib.bzl", "asserts", "shell", "unittest")

def _shell_array_literal_test(ctx):
    """Unit tests for shell.array_literal."""
    env = unittest.begin(ctx)

    asserts.equals(env, "()", shell.array_literal([]))
    asserts.equals(env, "('1')", shell.array_literal([1]))
    asserts.equals(env, "('1' '2' '3')", shell.array_literal([1, 2, 3]))
    asserts.equals(env, "('$foo')", shell.array_literal(["$foo"]))
    asserts.equals(env, "('qu\"o\"te')", shell.array_literal(['qu"o"te']))

    unittest.end(env)

shell_array_literal_test = unittest.make(_shell_array_literal_test)

def _shell_quote_test(ctx):
    """Unit tests for shell.quote."""
    env = unittest.begin(ctx)

    asserts.equals(env, "'foo'", shell.quote("foo"))
    asserts.equals(env, "'foo bar'", shell.quote("foo bar"))
    asserts.equals(env, "'three   spaces'", shell.quote("three   spaces"))
    asserts.equals(env, "'  leading'", shell.quote("  leading"))
    asserts.equals(env, "'trailing  '", shell.quote("trailing  "))
    asserts.equals(env, "'new\nline'", shell.quote("new\nline"))
    asserts.equals(env, "'tab\tcharacter'", shell.quote("tab\tcharacter"))
    asserts.equals(env, "'$foo'", shell.quote("$foo"))
    asserts.equals(env, "'qu\"o\"te'", shell.quote('qu"o"te'))
    asserts.equals(env, "'it'\\''s'", shell.quote("it's"))
    asserts.equals(env, "'foo\\bar'", shell.quote("foo\\bar"))
    asserts.equals(env, "'back`echo q`uote'", shell.quote("back`echo q`uote"))

    unittest.end(env)

shell_quote_test = unittest.make(_shell_quote_test)

def _shell_spawn_e2e_test_impl(ctx):
    """Test spawning a real shell."""
    args = [
        "foo",
        "foo bar",
        "three   spaces",
        "  leading",
        "trailing  ",
        "new\nline",
        "tab\tcharacter",
        "$foo",
        'qu"o"te',
        "it's",
        "foo\\bar",
        "back`echo q`uote",
    ]
    script_content = "\n".join([
        "#!/bin/bash",
        "myarray=" + shell.array_literal(args),
        'output=$(echo "${myarray[@]}")',
        # For logging:
        'echo "DEBUG: output=[${output}]" >&2',
        # The following is a shell representation of what the echo of the quoted
        # array will look like.  It looks a bit confusing considering it's shell
        # quoted into Python.  Shell using single quotes to minimize shell
        # escaping, so only the single quote needs to be escaped as '\'', all
        # others are essentially kept literally.
        "expected='foo foo bar three   spaces   leading trailing   new",
        "line tab\tcharacter $foo qu\"o\"te it'\\''s foo\\bar back`echo q`uote'",
        '[[ "${output}" == "${expected}" ]]',
    ])
    ctx.actions.write(
        output = ctx.outputs.executable,
        content = script_content,
        is_executable = True,
    )
    return struct()

shell_spawn_e2e_test = rule(
    test = True,
    implementation = _shell_spawn_e2e_test_impl,
)

def shell_test_suite():
    """Creates the test targets and test suite for shell.bzl tests."""
    unittest.suite(
        "shell_tests",
        shell_array_literal_test,
        shell_quote_test,
        shell_spawn_e2e_test,
    )
