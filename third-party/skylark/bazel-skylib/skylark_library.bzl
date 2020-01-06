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

"""Skylib module containing a library rule for aggregating rules files."""

SkylarkLibraryInfo = provider(
    "Information on contained Skylark rules.",
    fields = {
        "srcs": "Top level rules files.",
        "transitive_srcs": "Transitive closure of rules files required for " +
                           "interpretation of the srcs",
    },
)

def _skylark_library_impl(ctx):
    all_files = depset(ctx.files.srcs, order = "postorder")
    for dep in ctx.attr.deps:
        all_files += dep.files
    return [
        # All dependent files should be listed in both `files` and in `runfiles`;
        # this ensures that a `skylark_library` can be referenced as `data` from
        # a separate program, or from `tools` of a genrule().
        DefaultInfo(
            files = all_files,
            runfiles = ctx.runfiles(files = all_files.to_list()),
        ),

        # We also define our own provider struct, for aggregation and testing.
        SkylarkLibraryInfo(
            srcs = ctx.files.srcs,
            transitive_srcs = all_files,
        ),
    ]

skylark_library = rule(
    implementation = _skylark_library_impl,
    attrs = {
        "srcs": attr.label_list(
            allow_files = [".bzl"],
        ),
        "deps": attr.label_list(
            allow_files = [".bzl"],
            providers = [
                [SkylarkLibraryInfo],
            ],
        ),
    },
)
"""Creates a logical collection of Skylark .bzl files.

Args:
  srcs: List of `.bzl` files that are processed to create this target.
  deps: List of other `skylark_library` targets that are required by the
    Skylark files listed in `srcs`.

Example:
  Suppose your project has the following structure:

  ```
  [workspace]/
      WORKSPACE
      BUILD
      checkstyle/
          BUILD
          checkstyle.bzl
      lua/
          BUILD
          lua.bzl
          luarocks.bzl
  ```

  In this case, you can have `skylark_library` targets in `checkstyle/BUILD` and
  `lua/BUILD`:

  `checkstyle/BUILD`:

  ```python
  load("@buck_bazel_skylib//:skylark_library.bzl", "skylark_library")

  skylark_library(
      name = "checkstyle-rules",
      srcs = ["checkstyle.bzl"],
  )
  ```

  `lua/BUILD`:

  ```python
  load("@buck_bazel_skylib//:skylark_library.bzl", "skylark_library")

  skylark_library(
      name = "lua-rules",
      srcs = [
          "lua.bzl",
          "luarocks.bzl",
      ],
  )
  ```
"""
