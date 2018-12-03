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
import itertools
import os
import sys

from artificialproject.field_generators import (
    BuildTargetSetGenerator,
    EnumSetGenerator,
    NullableGenerator,
    PathSetGenerator,
    SingletonGenerator,
    SourcePathSetGenerator,
    SourcesWithFlagsGenerator,
    StringGenerator,
    VisibilityGenerator,
)
from artificialproject.random import weighted_choice

Context = collections.namedtuple(
    "Context",
    [
        "input_target_data",
        "gen_target_data",
        "gen_targets_by_type",
        "gen_targets_with_output_by_type",
        "output_repository",
        "file_path_generator",
    ],
)


class TargetDataGenerator:
    def __init__(self, type, target_name_generator, context):
        self._type = type
        self._context = context
        self._target_name_generator = target_name_generator
        self._generators = {}

    def _get_field_generator(self, target_type, field_name):
        def nullable(generator_class):
            return lambda context: NullableGenerator(generator_class(context))

        def singleton(set_generator_class):
            return lambda context: SingletonGenerator(set_generator_class(context))

        def enum_set(context):
            return EnumSetGenerator()

        def string(context):
            return StringGenerator()

        def file_name(context):
            return StringGenerator(respect_file_extensions=True)

        def visibility(context):
            return VisibilityGenerator()

        def annotation_processor_deps(context):
            # Java binaries can only be used as annotation processors if they
            # have no native library dependencies, which is difficult to
            # detect. Instead always use java libraries.
            return BuildTargetSetGenerator(
                context, override_types={"java_binary": "java_library"}
            )

        build_target_set = BuildTargetSetGenerator
        build_target = singleton(build_target_set)
        enum = singleton(enum_set)
        paths = PathSetGenerator
        path = singleton(paths)
        source_path_set = SourcePathSetGenerator
        source_path = singleton(source_path_set)
        sources_with_flags = SourcesWithFlagsGenerator

        FIELDS = {
            "*.annotation_processor_deps": annotation_processor_deps,
            "*.annotation_processor_params": enum_set,
            "*.annotation_processors": enum_set,
            "*.annotation_processors_only": enum,
            "*.deps": build_target_set,
            "*.exported_deps": build_target_set,
            "*.extra_arguments": enum_set,
            "*.java_version": enum,
            "*.licenses": enum_set,
            "*.out": nullable(file_name),
            "*.provided_deps": build_target_set,
            "*.remove_classes": enum_set,
            "*.resources": source_path_set,
            "*.source": enum,
            "*.srcs": source_path_set,
            "*.target": enum,
            "*.visibility": visibility,
            "android_binary.keystore": build_target,
            "android_binary.manifest": source_path,
            "android_build_config.package": string,
            "android_manifest.skeleton": source_path,
            "android_react_native_library.bundle_name": string,
            "android_react_native_library.entry_path": source_path,
            "android_react_native_library.package": string,
            "cxx_library.srcs": sources_with_flags,
            "export_file.mode": enum,
            "export_file.src": source_path,
            "gen_aidl.aidl": source_path,
            "gen_aidl.import_path": string,
            "keystore.properties": source_path,
            "keystore.store": source_path,
            "prebuilt_jar.binary_jar": source_path,
            "prebuilt_native_library.native_libs": path,
            "python_library.resources": None,
            "python_library.srcs": None,
            "sh_binary.main": source_path,
            "worker_tool.exe": build_target,
        }

        sentinel = object()

        generator_class = FIELDS.get(target_type + "." + field_name, sentinel)
        if generator_class is None:
            return None
        elif generator_class is not sentinel:
            return generator_class(self._context)

        generator_class = FIELDS.get("*." + field_name, sentinel)
        if generator_class is not sentinel:
            return generator_class(self._context)

        return None

    def add_sample(self, sample):
        target_type = sample["buck.type"]
        base_path = sample["buck.base_path"]
        if target_type == "export_file" and sample["src"] is None:
            sample["src"] = sample["name"]
        for field, value in sample.items():
            if "." in field or field == "name":
                continue
            if target_type == "android_build_config" and field == "java_package":
                field = "package"
            if (
                target_type in ["android_resource", "android_react_native_library"]
                and field == "r_dot_java_package"
            ):
                field = "package"
            if field in self._generators:
                generator = self._generators[field]
            else:
                generator = self._get_field_generator(target_type, field)
                self._generators[field] = generator
                if generator is None:
                    print(
                        "Warning: no generator for {}.{}".format(target_type, field),
                        file=sys.stderr,
                    )
            if generator is None:
                continue
            generator.add_sample(base_path, value)

    def generate(self):
        base_path = self._context.file_path_generator.generate_package_path()
        result = {
            "name": self._target_name_generator(),
            "buck.type": self._type,
            "buck.base_path": base_path,
        }
        all_deps = set()
        for field, generator in self._generators.items():
            if generator is None:
                continue
            generated_field = generator.generate(base_path)
            result[field] = generated_field.value
            all_deps.update(generated_field.deps)
        if self._type == "ndk_library":
            makefile_path = os.path.join(base_path, "Android.mk")
            self._register_and_create_file_at_path(makefile_path)
        elif self._type == "prebuilt_cxx_library":
            library_path = os.path.join(
                base_path, "lib", "lib{}.a".format(result["name"])
            )
            self._register_and_create_file_at_path(library_path)
        result[".all_deps"] = all_deps
        return result

    def _register_and_create_file_at_path(self, path):
        self._context.file_path_generator.register_path(path)
        path = os.path.join(self._context.output_repository, path)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "a"):
            pass


class TargetGenerator:
    def __init__(self, context):
        self._types = collections.Counter()
        self._data_generators = {}
        target_id = itertools.count()

        def target_name_generator():
            return "t" + str(next(target_id))

        self._create_new_data_generator = lambda type: TargetDataGenerator(
            type, target_name_generator, context
        )

    def add_sample(self, sample):
        type = sample["buck.type"]
        if type not in self._data_generators:
            self._data_generators[type] = self._create_new_data_generator(type)
        self._data_generators[type].add_sample(sample)
        self._types.update([type])

    def generate(self, force_type=None):
        if force_type is not None:
            type = force_type
        else:
            type = weighted_choice(self._types)
        return self._data_generators[type].generate()
