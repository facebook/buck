#!/usr/bin/env python3
# Copyright (c) Facebook, Inc. and its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from collections import defaultdict
from contextlib import contextmanager
from pathlib import Path
from typing import DefaultDict, Dict, Iterator


class BuckConfig:
    """ A BuckConfig object for automatically saving configurations to file """

    def __init__(self, path: Path) -> None:
        self._config: DefaultDict[str, Dict[str, str]] = defaultdict(dict)
        self.path = path
        self._save()

    def __repr__(self) -> str:
        """
        File representation of BuckConfig object
        """
        contents = []
        for section, options in self._config.items():
            if options:
                contents.append(f"[{section}]\n\n")
                for option_key, option_value in options.items():
                    contents.append(f"{option_key} = {option_value}\n")
                contents.append("\n")
        return "".join(contents)

    def _save(self) -> None:
        """
        Saves existing self._config to file
        """
        with open(self.path, mode="w") as f:
            f.write(repr(self))

    @contextmanager
    def modify(self) -> Iterator[DefaultDict[str, Dict[str, str]]]:
        """
        A context manager that yields the configs as a dictionary
        Used for setting, getting, and deleting configs
        On close, this context manager will call self._save() to save the new configs to file.
        """
        yield self._config
        self._save()
