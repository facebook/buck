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

"""Glob implementation in python."""

from .util import is_special


def path_component_starts_with_dot(relative_path):
    for p in relative_path.parts:
        if p.startswith("."):
            return True
    return False


def glob_internal(
    includes,
    excludes,
    project_root_relative_excludes,
    include_dotfiles,
    search_base,
    project_root,
):
    def includes_iterator():
        for pattern in includes:
            for path in search_base.glob(pattern):
                # TODO(beng): Handle hidden files on Windows.
                if path.is_file() and (
                    include_dotfiles
                    or not path_component_starts_with_dot(path.relative_to(search_base))
                ):
                    yield path

    non_special_excludes = set()
    match_excludes = set()
    for pattern in excludes:
        if is_special(pattern):
            match_excludes.add(pattern)
        else:
            non_special_excludes.add(pattern)

    def exclusion(path):
        relative_to_search_base = path.relative_to(search_base)
        if relative_to_search_base.as_posix() in non_special_excludes:
            return True
        for pattern in match_excludes:
            result = relative_to_search_base.match(pattern, match_entire=True)
            if result:
                return True
        relative_to_project_root = path.relative_to(project_root)
        for pattern in project_root_relative_excludes:
            result = relative_to_project_root.match(pattern, match_entire=True)
            if result:
                return True
        return False

    return sorted(
        set(
            [
                str(p.relative_to(search_base))
                for p in includes_iterator()
                if not exclusion(p)
            ]
        )
    )


__all__ = [glob_internal]
