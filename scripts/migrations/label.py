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

import collections
import os
import re
import typing


class Label(collections.namedtuple("Label", ["cell", "package", "name"])):
    """
    Identifies a build target. All build targets belong to exactly one package
    and their names are called their labels. Examples of labels:
        //dir1/dir2:target_name
    where 'dir1/dir2' is the package containing a build file, and 'target_name'
    represents the target within the package.
    """

    def get_build_file_path(
        self, cell_roots: typing.Dict[str, str], build_file_name: str = "BUCK"
    ) -> str:
        return os.path.join(cell_roots[self.cell], self.package, build_file_name)

    def to_import_string(self):
        """Convert this label to a string representing include file label."""
        cell = self.cell or ""
        return (
            cell
            + "//"
            + self.package
            + (":" + self.name if self.name is not None else "")
        )


__LABEL_PATTERN = re.compile(
    "(?P<cell>[\w-]+)?//(?P<package>[\w./-]+)(:(?P<name>[\w-]+))?"
)


def from_string(string: str) -> Label:
    match = __LABEL_PATTERN.match(string)
    assert match, "Invalid label " + repr(string)
    return Label(match.group("cell"), match.group("package"), match.group("name"))
