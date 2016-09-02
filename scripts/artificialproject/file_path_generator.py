import collections
import os

from artificialproject.field_generators import (
    GenerationFailedException,
    StringGenerator,
)
from artificialproject.random import weighted_choice


class FilePathGenerator:
    BUILD_FILE_NAME = 'BUCK'

    def __init__(self):
        self._component_generator = StringGenerator()
        self._package_depths = collections.Counter()
        self._sizes_by_depth = collections.defaultdict(collections.Counter)
        self._root = {}
        self._package_paths = {}
        self._available_directories = {}

    def analyze_project_data(self, project_data):
        dir_entries = collections.defaultdict(set)
        for target_data in project_data.values():
            base_path = target_data['buck.base_path']
            components = []
            while base_path:
                base_path, component = os.path.split(base_path)
                self._component_generator.add_string_sample(component)
                components.append(component)
            self._package_depths.update([len(components)])
            components = components[::-1]
            for i in range(len(components)):
                prefix = components[:i]
                name = components[i]
                dir_entries[tuple(prefix)].add(name)
        for path, entries in dir_entries.items():
            self._sizes_by_depth[len(path)].update([len(entries)])

    def generate_package_path(self):
        depth = weighted_choice(self._package_depths)
        path, parent_dir = self._generate_path(
                '//', self._root, depth, self._sizes_by_depth,
                self._component_generator)
        directory = {self.BUILD_FILE_NAME: None}
        parent_dir[os.path.basename(path)] = directory
        return path

    def _generate_path(
            self, package_key, root, depth, sizes_by_depth,
            component_generator):
        assert depth >= 1
        parent_path, parent_dir = self._generate_parent(
                package_key, root, depth - 1, sizes_by_depth,
                component_generator)
        name = self._generate_name(parent_dir, component_generator)
        return os.path.join(parent_path, name), parent_dir

    def _generate_parent(
            self, package_key, root, depth, sizes_by_depth,
            component_generator):
        if depth == 0:
            return '', root
        key = (package_key, depth)
        value = self._available_directories.get(key)
        if value is not None:
            key_found = True
            path, directory, size = value
        else:
            key_found = False
            parent_path, parent_dir = self._generate_parent(
                    package_key, root, depth - 1, sizes_by_depth,
                    component_generator)
            name = self._generate_name(parent_dir, component_generator)
            path = os.path.join(parent_path, name)
            directory = {}
            parent_dir[name] = directory
            size = weighted_choice(sizes_by_depth[depth])
        size -= 1
        if size > 0:
            self._available_directories[key] = (path, directory, size)
        elif key_found:
            del self._available_directories[key]
        return path, directory

    def _generate_name(self, directory, generator):
        for i in range(1000):
            name = generator.generate_string()
            if name not in directory and name != self.BUILD_FILE_NAME:
                return name
        raise GenerationFailedException()
