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

"""Skylib module containing convenience interfaces for select()."""

def _with_or(input_dict):
  """Drop-in replacement for `select()` that supports ORed keys.

  Args:
    input_dict: The same dictionary `select()` takes, except keys may take
        either the usual form `"//foo:config1"` or
        `("//foo:config1", "//foo:config2", ...)` to signify
        `//foo:config1` OR `//foo:config2` OR `...`.

        Example:

        ```build
        deps = selects.with_or({
            "//configs:one": [":dep1"],
            ("//configs:two", "//configs:three"): [":dep2or3"],
            "//configs:four": [":dep4"],
            "//conditions:default": [":default"]
        })
        ```

        Key labels may appear at most once anywhere in the input.

  Returns:
    A native `select()` that expands

    `("//configs:two", "//configs:three"): [":dep2or3"]`

    to

    ```build
    "//configs:two": [":dep2or3"],
    "//configs:three": [":dep2or3"],
    ```
  """
  return select(_with_or_dict(input_dict))


def _with_or_dict(input_dict):
  """Variation of `with_or` that returns the dict of the `select()`.

  Unlike `select()`, the contents of the dict can be inspected by Skylark
  macros.

  Args:
    input_dict: Same as `with_or`.

  Returns:
    A dictionary usable by a native `select()`.
  """
  output_dict = {}
  for (key, value) in input_dict.items():
    if type(key) == type(()):
      for config_setting in key:
        if config_setting in output_dict.keys():
          fail("key %s appears multiple times" % config_setting)
        output_dict[config_setting] = value
    else:
      if key in output_dict.keys():
        fail("key %s appears multiple times" % config_setting)
      output_dict[key] = value
  return output_dict


selects = struct(
    with_or=_with_or,
    with_or_dict=_with_or_dict
)
