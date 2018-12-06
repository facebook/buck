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

"""Provides export_files macro."""

_DEFAULT_VISIBILITY = ["PUBLIC"]

def export_files(files, visibility = None, licenses = None):
    """Exports passed files to other packages.

    Args:
      files: file paths from this package that should be exported.
      visibility: targets provided paths should be visible to.
      licenses: license files for the exported files.
    """
    visibility = visibility or _DEFAULT_VISIBILITY
    for filename in files:
        native.export_file(name = filename, visibility = visibility, licenses = licenses)
