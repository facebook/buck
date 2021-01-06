# Copyright 2018-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

"""Provides file_alias macro."""

_DEFAULT_VISIBILITY = ["PUBLIC"]

def file_alias(name, source, visibility = None):
    """Exports a file from source in current package.

    This is useful for renaming files that are passed as resources.

    Args:
      name: output file name.
      source: path or label identifying the original file.
      visibility: targets this file should be made visible to.
    """
    visibility = visibility if visibility != None else _DEFAULT_VISIBILITY
    native.export_file(name = name, src = source, visibility = visibility)
