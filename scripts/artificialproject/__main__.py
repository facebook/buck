#!/usr/bin/env python3

import argparse
import collections
import json
import os
import subprocess
import sys

from artificialproject.field_generators import GenerationFailedException
from artificialproject.file_path_generator import FilePathGenerator
from artificialproject.target_generator import Context, TargetGenerator


def get_transitive_deps(top_target_name, targets_by_name):
    visited = set([top_target_name])
    to_process = [top_target_name]
    while to_process:
        target_name = to_process.pop()
        target = targets_by_name[target_name]
        for dep in target[".all_deps"]:
            if dep not in visited:
                to_process.append(dep)
                visited.add(dep)
    return visited


def target_has_output(target):
    type = target["buck.type"]
    if type in ["java_library", "android_library"]:
        srcs = target.get("srcs", [])
        return len(srcs) != 0
    return True


def main():
    parser = argparse.ArgumentParser(description="Generate artificial Buck project.")
    parser.add_argument(
        "--input-repo", default=".", help="Path to the repository to analyze."
    )
    parser.add_argument(
        "--input-target", required=True, help="Name of the target to analyze."
    )
    parser.add_argument(
        "--output-repo", required=True, help="Output path for generated repository."
    )
    parser.add_argument(
        "--output-repo-size",
        type=int,
        required=True,
        help="Approximate number of targets to generate.",
    )
    args = parser.parse_args()
    args.input_target = (
        subprocess.check_output(
            ["buck", "targets", args.input_target], cwd=args.input_repo
        )
        .decode("utf8")
        .strip("\n")
    )
    project_data_json = subprocess.check_output(
        [
            "buck",
            "query",
            "deps({})".format(args.input_target),
            "--output-attributes",
            ".*",
        ],
        cwd=args.input_repo,
    )
    project_data = json.loads(project_data_json.decode("utf8"))
    targets_by_name = {}
    gen_targets_by_type = collections.defaultdict(list)
    gen_targets_with_output_by_type = collections.defaultdict(list)
    file_path_generator = FilePathGenerator()
    file_path_generator.analyze_project_data(project_data)
    context = Context(
        project_data,
        targets_by_name,
        gen_targets_by_type,
        gen_targets_with_output_by_type,
        args.output_repo,
        file_path_generator,
    )
    target_generator = TargetGenerator(context)
    for target_name, target_data in project_data.items():
        target_generator.add_sample(target_data)

    try:
        os.makedirs(args.output_repo)
    except FileExistsError:
        print("Output repository already exists.", file=sys.stderr)
        return 1
    with open(os.path.join(args.output_repo, ".buckconfig"), "w"):
        pass

    rejected = 0
    count = 0
    target_size = args.output_repo_size
    candidates = []
    while True:
        try:
            target = target_generator.generate()
        except GenerationFailedException:
            rejected += 1
            continue
        count += 1
        target_name = "//" + target["buck.base_path"] + ":" + target["name"]
        gen_targets_by_type[target["buck.type"]].append(target_name)
        if target_has_output(target):
            gen_targets_with_output_by_type[target["buck.type"]].append(target_name)
        targets_by_name[target_name] = target
        if count % 100 == 0:
            try:
                target = target_generator.generate(
                    force_type=project_data[args.input_target]["buck.type"]
                )
            except GenerationFailedException:
                continue
            target_name = "//" + target["buck.base_path"] + ":" + target["name"]
            targets_by_name[target_name] = target
            transitive_deps = len(get_transitive_deps(target_name, targets_by_name))
            del targets_by_name[target_name]
            candidates.append((transitive_deps, target))
            candidates.sort(key=lambda c: c[0])
            MIN_CANDIDATES = 100
            candidates = candidates[-MIN_CANDIDATES:]
            progress = 0
            if len(candidates) >= MIN_CANDIDATES:
                progress = candidates[0][0]
            else:
                progress += len(candidates) - MIN_CANDIDATES
            progress_field_width = len(str(target_size)) + 1
            print(
                "\rProgress: {:{width}} / {}    ".format(
                    progress, target_size, width=progress_field_width
                ),
                end="",
                flush=True,
                file=sys.stderr,
            )
            if len(candidates) >= MIN_CANDIDATES and candidates[0][0] >= target_size:
                target = candidates[0][1]
                gen_targets_by_type[target["buck.type"]].append(target["name"])
                targets_by_name[target["name"]] = target
                generated_top_target = target
                break
    print(file=sys.stderr)
    print("Rejection rate: {:.2%}".format(1.0 * rejected / count), file=sys.stderr)

    print(
        "Top target: //{}:{}".format(
            generated_top_target["buck.base_path"], generated_top_target["name"]
        ),
        file=sys.stderr,
    )

    targets_by_base_path = collections.defaultdict(list)
    for target in targets_by_name.values():
        targets_by_base_path[target["buck.base_path"]].append(target)

    for base_path, targets in targets_by_base_path.items():
        buck_file_path = os.path.join(args.output_repo, base_path, "BUCK")
        os.makedirs(os.path.dirname(buck_file_path), exist_ok=True)
        with open(buck_file_path, "w") as buck_file:
            for target in targets:
                buck_file.write("{}(\n".format(target["buck.type"]))
                for key in target:
                    if "." not in key:
                        buck_file.write("  {}={!r},\n".format(key, target[key]))
                buck_file.write(")\n\n")

    return 0


sys.exit(main())
