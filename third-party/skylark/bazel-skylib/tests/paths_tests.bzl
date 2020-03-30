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

"""Unit tests for paths.bzl."""

load("//:lib.bzl", "asserts", "paths", "unittest")

def _basename_test(ctx):
    """Unit tests for paths.basename."""
    env = unittest.begin(ctx)

    # Verify some degenerate cases.
    asserts.equals(env, "", paths.basename(""))
    asserts.equals(env, "", paths.basename("/"))
    asserts.equals(env, "bar", paths.basename("foo///bar"))

    # Verify some realistic cases.
    asserts.equals(env, "foo", paths.basename("foo"))
    asserts.equals(env, "foo", paths.basename("/foo"))
    asserts.equals(env, "foo", paths.basename("bar/foo"))
    asserts.equals(env, "foo", paths.basename("/bar/foo"))

    # Verify that we correctly duplicate Python's os.path.basename behavior,
    # where a trailing slash means the basename is empty.
    asserts.equals(env, "", paths.basename("foo/"))
    asserts.equals(env, "", paths.basename("/foo/"))

    unittest.end(env)

basename_test = unittest.make(_basename_test)

def _dirname_test(ctx):
    """Unit tests for paths.dirname."""
    env = unittest.begin(ctx)

    # Verify some degenerate cases.
    asserts.equals(env, "", paths.dirname(""))
    asserts.equals(env, "/", paths.dirname("/"))
    asserts.equals(env, "foo", paths.dirname("foo///bar"))

    # Verify some realistic cases.
    asserts.equals(env, "", paths.dirname("foo"))
    asserts.equals(env, "/", paths.dirname("/foo"))
    asserts.equals(env, "bar", paths.dirname("bar/foo"))
    asserts.equals(env, "/bar", paths.dirname("/bar/foo"))

    # Verify that we correctly duplicate Python's os.path.dirname behavior,
    # where a trailing slash means the dirname is the same as the original
    # path (without the trailing slash).
    asserts.equals(env, "foo", paths.dirname("foo/"))
    asserts.equals(env, "/foo", paths.dirname("/foo/"))

    unittest.end(env)

dirname_test = unittest.make(_dirname_test)

def _is_absolute_test(ctx):
    """Unit tests for paths.is_absolute."""
    env = unittest.begin(ctx)

    # Try a degenerate case.
    asserts.false(env, paths.is_absolute(""))

    # Try some relative paths.
    asserts.false(env, paths.is_absolute("foo"))
    asserts.false(env, paths.is_absolute("foo/"))
    asserts.false(env, paths.is_absolute("foo/bar"))

    # Try some absolute paths.
    asserts.true(env, paths.is_absolute("/"))
    asserts.true(env, paths.is_absolute("/foo"))
    asserts.true(env, paths.is_absolute("/foo/"))
    asserts.true(env, paths.is_absolute("/foo/bar"))

    unittest.end(env)

is_absolute_test = unittest.make(_is_absolute_test)

def _join_test(ctx):
    """Unit tests for paths.join."""
    env = unittest.begin(ctx)

    # Try a degenerate case.
    asserts.equals(env, "", paths.join(""))

    # Try some basic paths.
    asserts.equals(env, "foo", paths.join("foo"))
    asserts.equals(env, "foo/bar", paths.join("foo", "bar"))
    asserts.equals(env, "foo/bar/baz", paths.join("foo", "bar", "baz"))

    # Make sure an initially absolute path stays absolute.
    asserts.equals(env, "/foo", paths.join("/foo"))
    asserts.equals(env, "/foo/bar", paths.join("/foo", "bar"))

    # Make sure an absolute path later in the list resets the result.
    asserts.equals(env, "/baz", paths.join("foo", "bar", "/baz"))
    asserts.equals(env, "/baz", paths.join("foo", "/bar", "/baz"))
    asserts.equals(env, "/bar/baz", paths.join("foo", "/bar", "baz"))
    asserts.equals(env, "/bar", paths.join("/foo", "/bar"))

    # Make sure a leading empty segment doesn't make it absolute.
    asserts.equals(env, "foo", paths.join("", "foo"))

    # Try some trailing slash scenarios.
    asserts.equals(env, "foo/", paths.join("foo", ""))
    asserts.equals(env, "foo/", paths.join("foo/"))
    asserts.equals(env, "foo/", paths.join("foo/", ""))
    asserts.equals(env, "foo//", paths.join("foo//", ""))
    asserts.equals(env, "foo//", paths.join("foo//"))
    asserts.equals(env, "foo/bar/baz/", paths.join("foo/", "bar/", "baz", ""))
    asserts.equals(env, "foo/bar/baz/", paths.join("foo/", "bar/", "baz/"))
    asserts.equals(env, "foo/bar/baz/", paths.join("foo/", "bar/", "baz/", ""))

    # Make sure that adjacent empty segments don't add extra path separators.
    asserts.equals(env, "foo/", paths.join("foo", "", ""))
    asserts.equals(env, "foo", paths.join("", "", "foo"))
    asserts.equals(env, "foo/bar", paths.join("foo", "", "", "bar"))

    unittest.end(env)

join_test = unittest.make(_join_test)

def _normalize_test(ctx):
    """Unit tests for paths.normalize."""
    env = unittest.begin(ctx)

    # Try the most basic case.
    asserts.equals(env, ".", paths.normalize(""))

    # Try some basic adjacent-slash removal.
    asserts.equals(env, "foo/bar", paths.normalize("foo//bar"))
    asserts.equals(env, "foo/bar", paths.normalize("foo////bar"))

    # Try some "." removal.
    asserts.equals(env, "foo/bar", paths.normalize("foo/./bar"))
    asserts.equals(env, "foo/bar", paths.normalize("./foo/bar"))
    asserts.equals(env, "foo/bar", paths.normalize("foo/bar/."))
    asserts.equals(env, "/", paths.normalize("/."))

    # Try some ".." removal.
    asserts.equals(env, "bar", paths.normalize("foo/../bar"))
    asserts.equals(env, "foo", paths.normalize("foo/bar/.."))
    asserts.equals(env, ".", paths.normalize("foo/.."))
    asserts.equals(env, ".", paths.normalize("foo/bar/../.."))
    asserts.equals(env, "..", paths.normalize("foo/../.."))
    asserts.equals(env, "/", paths.normalize("/foo/../.."))
    asserts.equals(env, "../../c", paths.normalize("a/b/../../../../c/d/.."))

    # Make sure one or two initial slashes are preserved, but three or more are
    # collapsed to a single slash.
    asserts.equals(env, "/foo", paths.normalize("/foo"))
    asserts.equals(env, "//foo", paths.normalize("//foo"))
    asserts.equals(env, "/foo", paths.normalize("///foo"))

    # Trailing slashes should be removed unless the entire path is a trailing
    # slash.
    asserts.equals(env, "/", paths.normalize("/"))
    asserts.equals(env, "foo", paths.normalize("foo/"))
    asserts.equals(env, "foo/bar", paths.normalize("foo/bar/"))

    unittest.end(env)

normalize_test = unittest.make(_normalize_test)

def _relativize_test(ctx):
    """Unit tests for paths.relativize."""
    env = unittest.begin(ctx)

    # Make sure that relative-to-current-directory works in all forms.
    asserts.equals(env, "foo", paths.relativize("foo", ""))
    asserts.equals(env, "foo", paths.relativize("foo", "."))

    # Try some regular cases.
    asserts.equals(env, "bar", paths.relativize("foo/bar", "foo"))
    asserts.equals(env, "baz", paths.relativize("foo/bar/baz", "foo/bar"))
    asserts.equals(env, "bar/baz", paths.relativize("foo/bar/baz", "foo"))

    # Try a case where a parent directory is normalized away.
    asserts.equals(env, "baz", paths.relativize("foo/bar/../baz", "foo"))

    # TODO(allevato): Test failure cases, once that is possible.

    unittest.end(env)

relativize_test = unittest.make(_relativize_test)

def _replace_extension_test(ctx):
    """Unit tests for paths.replace_extension."""
    env = unittest.begin(ctx)

    # Try some degenerate cases.
    asserts.equals(env, ".foo", paths.replace_extension("", ".foo"))
    asserts.equals(env, "/.foo", paths.replace_extension("/", ".foo"))
    asserts.equals(env, "foo.bar", paths.replace_extension("foo", ".bar"))

    # Try a directory with an extension and basename that doesn't have one.
    asserts.equals(
        env,
        "foo.bar/baz.quux",
        paths.replace_extension("foo.bar/baz", ".quux"),
    )

    # Now try some things with legit extensions.
    asserts.equals(env, "a.z", paths.replace_extension("a.b", ".z"))
    asserts.equals(env, "a.b.z", paths.replace_extension("a.b.c", ".z"))
    asserts.equals(env, "a/b.z", paths.replace_extension("a/b.c", ".z"))
    asserts.equals(env, "a.b/c.z", paths.replace_extension("a.b/c.d", ".z"))
    asserts.equals(env, ".a/b.z", paths.replace_extension(".a/b.c", ".z"))
    asserts.equals(env, ".a.z", paths.replace_extension(".a.b", ".z"))

    # Verify that we don't insert a period on the extension if none is provided.
    asserts.equals(env, "foobaz", paths.replace_extension("foo.bar", "baz"))

    unittest.end(env)

replace_extension_test = unittest.make(_replace_extension_test)

def _split_extension_test(ctx):
    """Unit tests for paths.split_extension."""
    env = unittest.begin(ctx)

    # Try some degenerate cases.
    asserts.equals(env, ("", ""), paths.split_extension(""))
    asserts.equals(env, ("/", ""), paths.split_extension("/"))
    asserts.equals(env, ("foo", ""), paths.split_extension("foo"))

    # Try some paths whose basenames start with ".".
    asserts.equals(env, (".", ""), paths.split_extension("."))
    asserts.equals(env, (".bashrc", ""), paths.split_extension(".bashrc"))
    asserts.equals(env, ("foo/.bashrc", ""), paths.split_extension("foo/.bashrc"))
    asserts.equals(
        env,
        (".foo/.bashrc", ""),
        paths.split_extension(".foo/.bashrc"),
    )

    # Try some directories with extensions with basenames that don't have one.
    asserts.equals(env, ("foo.bar/baz", ""), paths.split_extension("foo.bar/baz"))
    asserts.equals(
        env,
        ("foo.bar/.bashrc", ""),
        paths.split_extension("foo.bar/.bashrc"),
    )

    # Now try some things that will actually get split.
    asserts.equals(env, ("a", ".b"), paths.split_extension("a.b"))
    asserts.equals(env, ("a.b", ".c"), paths.split_extension("a.b.c"))
    asserts.equals(env, ("a/b", ".c"), paths.split_extension("a/b.c"))
    asserts.equals(env, ("a.b/c", ".d"), paths.split_extension("a.b/c.d"))
    asserts.equals(env, (".a/b", ".c"), paths.split_extension(".a/b.c"))
    asserts.equals(env, (".a", ".b"), paths.split_extension(".a.b"))

    unittest.end(env)

split_extension_test = unittest.make(_split_extension_test)

def paths_test_suite():
    """Creates the test targets and test suite for paths.bzl tests."""
    unittest.suite(
        "paths_tests",
        basename_test,
        dirname_test,
        is_absolute_test,
        join_test,
        normalize_test,
        relativize_test,
        replace_extension_test,
        split_extension_test,
    )
