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
import random

from artificialproject.random import weighted_choice


class GenerationFailedException(Exception):
    pass


GeneratedField = collections.namedtuple("GeneratedField", ["value", "deps"])


class NullableGenerator:
    def __init__(self, value_generator):
        self._value_generator = value_generator
        self._null_values = collections.Counter()

    def add_sample(self, base_path, sample):
        if sample is None:
            self._null_values.update([True])
        else:
            self._null_values.update([False])
            self._value_generator.add_sample(base_path, sample)

    def generate(self, base_path):
        if weighted_choice(self._null_values):
            return GeneratedField(None, [])
        else:
            return self._value_generator.generate(base_path)


class SingletonGenerator:
    def __init__(self, set_generator):
        self._set_generator = set_generator

    def add_sample(self, base_path, sample):
        self._set_generator.add_sample(base_path, [sample])

    def generate(self, base_path):
        field = self._set_generator.generate(base_path)
        assert len(field.value) == 1, field
        return GeneratedField(field.value[0], field.deps)


class EnumSetGenerator:
    def __init__(self):
        self._lengths = collections.Counter()
        self._values = collections.Counter()

    def add_sample(self, base_path, sample):
        self._lengths.update([len(sample)])
        self._values.update(sample)

    def generate(self, base_path):
        length = weighted_choice(self._lengths)
        options = collections.Counter(self._values)
        output = []
        while len(output) < length:
            value = weighted_choice(options)
            output.append(value)
            del options[value]
        return GeneratedField(output, [])


class StringGenerator:
    def __init__(self, respect_file_extensions=False):
        self._respect_file_extensions = respect_file_extensions
        self._lengths = collections.Counter()
        self._first_chars = collections.Counter()
        self._other_chars = collections.Counter()
        if self._respect_file_extensions:
            self._extensions = collections.Counter()

    def add_sample(self, base_path, sample):
        self.add_string_sample(sample)

    def add_string_sample(self, sample):
        if self._respect_file_extensions:
            sample, extension = os.path.splitext(sample)
            self._extensions.update([extension])
        self._lengths.update([len(sample)])
        if sample:
            self._first_chars.update(sample[0])
        for ch in sample[1:]:
            self._other_chars.update(ch)

    def generate(self, base_path):
        return GeneratedField(self.generate_string(), [])

    def generate_string(self):
        length = weighted_choice(self._lengths)
        output = ""
        if length > 0:
            output += weighted_choice(self._first_chars)
        while len(output) < length:
            output += weighted_choice(self._other_chars)
        if self._respect_file_extensions:
            output += weighted_choice(self._extensions)
        return output


class VisibilityGenerator:
    def add_sample(self, base_path, sample):
        pass

    def generate(self, base_path):
        return GeneratedField(["PUBLIC"], [])


class BuildTargetSetGenerator:
    class DynamicFilteredList:
        def __init__(self, input_list, predicate):
            self._input_list = input_list
            self._predicate = predicate
            self._output_list = []
            self._processed = 0

        def get_values(self):
            input_len = len(self._input_list)
            while self._processed < input_len:
                value = self._input_list[self._processed]
                if self._predicate(value):
                    self._output_list.append(value)
                self._processed += 1
            return self._output_list

    def __init__(self, context, process_output_extensions=False, override_types=None):
        self._context = context
        self._process_output_extensions = process_output_extensions
        self._lengths = collections.Counter()
        self._types = collections.Counter()
        self._unique_values_by_type_and_extension = collections.defaultdict(set)
        self._unique_values_dirty = False
        self._choice_probability_by_type_and_extension = dict()
        self._accepted_targets_by_type = dict()
        self._accepted_targets_with_output_by_type = dict()
        if self._process_output_extensions:
            self._output_extensions_by_type = collections.defaultdict(
                collections.Counter
            )
        if override_types is None:
            self._override_types = {}
        else:
            self._override_types = dict(override_types)

    def add_sample(self, base_path, sample):
        self._lengths.update([len(sample)])
        for target in sample:
            target = target.split("#")[0]
            if target.startswith(":"):
                target = "//" + base_path + target
            target_data = self._context.input_target_data[target]
            target_type = target_data["buck.type"]
            target_type = self._override_types.get(target_type, target_type)
            self._types.update([target_type])
            extension = None
            if self._process_output_extensions:
                extension = self._get_output_extension(target_data)
                self._output_extensions_by_type[target_type].update([extension])
            self._unique_values_by_type_and_extension[(target_type, extension)].add(
                target
            )
            self._unique_values_dirty = True

    def _update_choice_probability(self):
        self._choice_probability_by_type_and_extension = dict()
        for (
            (type, extension),
            used_values,
        ) in self._unique_values_by_type_and_extension.items():
            all_values = (
                x
                for x in self._context.input_target_data.values()
                if x["buck.type"] == type
            )
            if self._process_output_extensions:
                all_values = (
                    x for x in all_values if self._get_output_extension(x) == extension
                )
            num = len(used_values)
            denom = sum(1 for x in all_values)
            probability = float(num) / denom
            key = (type, extension)
            self._choice_probability_by_type_and_extension[key] = probability

    def _is_accepted(self, target_name):
        target_data = self._context.gen_target_data[target_name]
        target_type = target_data["buck.type"]
        extension = None
        if self._process_output_extensions:
            extension = self._get_output_extension(target_data)
        probability = self._choice_probability_by_type_and_extension.get(
            (target_type, extension), 0
        )
        return random.uniform(0, 1) < probability

    def generate(self, base_path, force_length=None):
        if self._unique_values_dirty:
            self._update_choice_probability()
            self._unique_values_dirty = False
        if force_length is not None:
            length = force_length
        else:
            length = weighted_choice(self._lengths)
        type_extension_counts = collections.Counter()
        for i in range(length):
            type = weighted_choice(self._types)
            if self._process_output_extensions:
                extension = weighted_choice(self._output_extensions_by_type[type])
            else:
                extension = None
            type_extension_counts.update([(type, extension)])
        output = []
        if self._process_output_extensions:
            all_targets_dict = self._context.gen_targets_with_output_by_type
            accepted_targets_dict = self._accepted_targets_with_output_by_type
        else:
            all_targets_dict = self._context.gen_targets_by_type
            accepted_targets_dict = self._accepted_targets_by_type
        for (type, extension), count in type_extension_counts.items():
            options = accepted_targets_dict.get(type)
            if options is None:
                options = self.DynamicFilteredList(
                    all_targets_dict[type], lambda x: self._is_accepted(x)
                )
                accepted_targets_dict[type] = options
            options = options.get_values()
            if extension is not None:
                options = [
                    x
                    for x in options
                    if self._get_output_extension(self._context.gen_target_data[x])
                    == extension
                ]
            if count > len(options):
                raise GenerationFailedException()
            output.extend(random.sample(options, count))
        return GeneratedField(output, output)

    def _get_output_extension(self, target_data):
        if "out" not in target_data or target_data["out"] is None:
            return None
        extension = os.path.splitext(target_data["out"])[1]
        if extension == "":
            return None
        return extension


class PathSetGenerator:
    def __init__(self, context):
        self._context = context
        self._component_generator = StringGenerator()
        self._lengths = collections.Counter()
        self._component_counts = collections.Counter()
        self._extensions = collections.Counter()

    def add_sample(self, base_path, sample):
        self._lengths.update([len(sample)])
        for path in sample:
            self._context.file_path_generator.add_package_file_sample(base_path, path)
            components = []
            while path:
                path, component = os.path.split(path)
                components.append(component)
            self._component_counts.update([len(components)])
            if not components:
                self._extensions.update([""])
            else:
                components[0], extension = os.path.splitext(components[0])
                self._extensions.update([extension])
            for component in components:
                self._component_generator.add_sample(base_path, component)

    def generate(self, base_path, force_length=None):
        if force_length is not None:
            length = force_length
        else:
            length = weighted_choice(self._lengths)
        extension = weighted_choice(self._extensions)
        output = [self._generate_path(base_path, extension) for i in range(length)]
        return GeneratedField(output, [])

    def _generate_path(self, base_path, extension):
        component_count = weighted_choice(self._component_counts)
        path = self._context.file_path_generator.generate_path_in_package(
            base_path, component_count, self._component_generator, extension
        )
        full_path = os.path.join(self._context.output_repository, base_path, path)
        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with open(full_path, "w"):
            pass
        return path


class SourcePathSetGenerator:
    def __init__(self, context):
        self._build_target_set_generator = BuildTargetSetGenerator(
            context, process_output_extensions=True
        )
        self._path_set_generator = PathSetGenerator(context)
        self._lengths = collections.Counter()
        self._build_target_values = collections.Counter()

    def add_sample(self, base_path, sample):
        self._lengths.update([len(sample)])
        for source_path in sample:
            if source_path.startswith("//") or source_path.startswith(":"):
                self._build_target_values.update([True])
                self._build_target_set_generator.add_sample(base_path, [source_path])
            else:
                self._build_target_values.update([False])
                self._path_set_generator.add_sample(base_path, [source_path])

    def generate(self, base_path):
        length = weighted_choice(self._lengths)
        build_target_count = 0
        path_count = 0
        for i in range(length):
            if weighted_choice(self._build_target_values):
                build_target_count += 1
            else:
                path_count += 1
        build_targets = self._build_target_set_generator.generate(
            base_path, force_length=build_target_count
        )
        paths = self._path_set_generator.generate(base_path, force_length=path_count)
        assert len(build_targets.value) == build_target_count, (
            build_targets,
            build_target_count,
        )
        assert len(paths.value) == path_count, (paths, path_count)
        return GeneratedField(
            build_targets.value + paths.value, build_targets.deps + paths.deps
        )


class SourcesWithFlagsGenerator:
    def __init__(self, context):
        self._source_path_set_generator = SourcePathSetGenerator(context)
        self._flag_generator = StringGenerator()
        self._flag_counts = collections.Counter()

    def add_sample(self, base_path, sample):
        source_paths = []
        flag_lists = []
        for source_with_flags in sample:
            if isinstance(source_with_flags, list):
                source_paths.append(source_with_flags[0])
                flag_lists.append(source_with_flags[1])
            else:
                source_paths.append(source_with_flags)
                flag_lists.append([])
        self._source_path_set_generator.add_sample(base_path, source_paths)
        for flags in flag_lists:
            self._flag_counts.update([len(flags)])
            for flag in flags:
                self._flag_generator.add_sample(base_path, flag)

    def generate(self, base_path):
        source_paths = self._source_path_set_generator.generate(base_path)
        output = [
            self._generate_source_with_flags(base_path, sp) for sp in source_paths.value
        ]
        return GeneratedField(output, source_paths.deps)

    def _generate_source_with_flags(self, base_path, source_path):
        flag_count = weighted_choice(self._flag_counts)
        if flag_count == 0:
            return source_path
        flags = [
            self._flag_generator.generate(base_path).value for i in range(flag_count)
        ]
        return [source_path, flags]
