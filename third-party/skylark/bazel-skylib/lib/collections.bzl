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

"""Skylib module containing functions that operate on collections."""


def _after_each(separator, iterable):
  """Inserts `separator` after each item in `iterable`.

  Args:
    separator: The value to insert after each item in `iterable`.
    iterable: The list into which to intersperse the separator.
  Returns:
    A new list with `separator` after each item in `iterable`.
  """
  result = []
  for x in iterable:
    result.append(x)
    result.append(separator)

  return result


def _before_each(separator, iterable):
  """Inserts `separator` before each item in `iterable`.

  Args:
    separator: The value to insert before each item in `iterable`.
    iterable: The list into which to intersperse the separator.
  Returns:
    A new list with `separator` before each item in `iterable`.
  """
  result = []
  for x in iterable:
    result.append(separator)
    result.append(x)

  return result


def _uniq(iterable):
  """Returns a list of unique elements in `iterable`.

  Requires all the elements to be hashable.

  Args:
    iterable: An iterable to filter.
  Returns:
    A new list with all unique elements from `iterable`.
  """
  unique_elements = {element: None for element in iterable}
  return unique_elements.keys()


collections = struct(
    after_each=_after_each,
    before_each=_before_each,
    uniq=_uniq,
)
