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

from typing import Dict, Optional


class Repository:
    """Represents a code repository with .buckconfig file."""

    def __init__(self, root: str, cell_roots: Dict[str, str]) -> None:
        self.root = root
        self.cell_roots = cell_roots

    def get_cell_path(self, cell: Optional[str]) -> str:
        """Returns the path where provided cell is located."""
        if not cell:
            return self.root
        assert cell in self.cell_roots, cell + " is not a known root"
        return self.cell_roots[cell]
