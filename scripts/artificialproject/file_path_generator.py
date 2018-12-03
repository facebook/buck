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

from artificialproject.field_generators import (
    GenerationFailedException,
    StringGenerator,
)
from artificialproject.random import weighted_choice


class FilePathGenerator:
    BUILD_FILE_NAME = "BUCK"

    def __init__(self):
        self._component_generator = StringGenerator()
        self._file_samples = collections.defaultdict(
            lambda: collections.defaultdict(set)
        )
        self._file_samples_dirty = False
        self._package_depths = collections.Counter()
        self._file_depths_in_package = collections.Counter()
        self._sizes_by_depth = collections.defaultdict(collections.Counter)
        self._sizes_by_depth_in_package = collections.defaultdict(collections.Counter)
        self._build_file_sizes = collections.Counter()
        self._root = {}
        self._package_paths = {}
        self._available_directories = {}
        self._last_package_path = None
        self._last_package_remaining_targets = None

    def analyze_project_data(self, project_data):
        dir_entries = collections.defaultdict(set)
        build_file_entries = collections.defaultdict(set)
        for target_data in project_data.values():
            base_path = target_data["buck.base_path"]
            build_file_entries[base_path].add(target_data["name"])
            components = self._split_path_into_components(base_path)
            # TODO(jakubzika): Targets in the root of the repo are ignored
            # because _generate_path does not handle depth == 0.
            if components:
                self._package_depths.update([len(components)])
            for component in components:
                self._component_generator.add_string_sample(component)
            for i, name in enumerate(components):
                prefix = components[:i]
                dir_entries[tuple(prefix)].add(name)
        for base_path, names in build_file_entries.items():
            self._build_file_sizes.update([len(names)])
        for path, entries in dir_entries.items():
            self._sizes_by_depth[len(path)].update([len(entries)])

    def add_package_file_sample(self, package_path, relative_path):
        components = self._split_path_into_components(relative_path)
        self._file_depths_in_package.update([len(components)])
        for i, name in enumerate(components):
            prefix = components[:i]
            self._file_samples[package_path][tuple(prefix)].add(name)
        self._file_samples_dirty = True

    def generate_package_path(self):
        if self._last_package_path is not None:
            path = self._last_package_path
            self._last_package_remaining_targets -= 1
            if self._last_package_remaining_targets <= 0:
                self._last_package_path = None
            return path
        depth = weighted_choice(self._package_depths)
        path, parent_dir = self._generate_path(
            "//", self._root, depth, self._sizes_by_depth, self._component_generator
        )
        directory = {self.BUILD_FILE_NAME.lower(): None}
        parent_dir[os.path.basename(path).lower()] = directory
        self._last_package_path = path
        self._last_package_remaining_targets = (
            weighted_choice(self._build_file_sizes) - 1
        )
        return path

    def generate_path_in_package(
        self, package_path, depth, component_generator, extension
    ):
        if depth == 0:
            return ""
        if self._file_samples_dirty:
            self._sizes_by_depth_in_package.clear()
            for dir_entries in self._file_samples.values():
                for path, entries in dir_entries.items():
                    self._sizes_by_depth_in_package[len(path)].update([len(entries)])
            self._file_samples_dirty = False
        root = self._root
        components = self._split_path_into_components(package_path)
        for component in components:
            root = root[component.lower()]
        path, parent_dir = self._generate_path(
            package_path,
            root,
            depth,
            self._sizes_by_depth_in_package,
            component_generator,
            extension,
        )
        parent_dir[os.path.basename(path).lower()] = None
        return path

    def register_path(self, path):
        directory = self._root
        existed = True
        for component in self._split_path_into_components(path):
            if component not in directory:
                directory[component] = {}
                existed = False
            directory = directory[component]
            if directory is None:
                raise GenerationFailedException()
        if existed:
            raise GenerationFailedException()

    def _split_path_into_components(self, path):
        components = []
        while path:
            path, component = os.path.split(path)
            components.append(component)
        return components[::-1]

    def _generate_path(
        self,
        package_key,
        root,
        depth,
        sizes_by_depth,
        component_generator,
        extension=None,
    ):
        assert depth >= 1
        parent_path, parent_dir = self._generate_parent(
            package_key, root, depth - 1, sizes_by_depth, component_generator
        )
        name = self._generate_name(parent_dir, component_generator, extension)
        return os.path.join(parent_path, name), parent_dir

    def _generate_parent(
        self, package_key, root, depth, sizes_by_depth, component_generator
    ):
        if depth == 0:
            return "", root
        key = (package_key, depth)
        value = self._available_directories.get(key)
        if value is not None:
            key_found = True
            path, directory, size = value
        else:
            key_found = False
            parent_path, parent_dir = self._generate_parent(
                package_key, root, depth - 1, sizes_by_depth, component_generator
            )
            name = self._generate_name(parent_dir, component_generator)
            path = os.path.join(parent_path, name)
            directory = {}
            parent_dir[name.lower()] = directory
            size = weighted_choice(sizes_by_depth[depth])
        size -= 1
        if size > 0:
            self._available_directories[key] = (path, directory, size)
        elif key_found:
            del self._available_directories[key]
        return path, directory

    def _generate_name(self, directory, generator, extension=None):
        for i in range(1000):
            name = generator.generate_string()
            if extension is not None:
                name += extension
            if (
                name.lower() not in directory
                and name.lower() != self.BUILD_FILE_NAME.lower()
            ):
                return name
        raise GenerationFailedException()
