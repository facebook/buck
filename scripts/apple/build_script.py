#!/usr/bin/env python3.6
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

# pyre-strict

import json
import os
import shutil
import subprocess
import sys
from typing import Any, Dict


def main() -> None:  # noqa C901
    # Xcode Environment
    arch = os.environ.get("ARCHS", default="")
    platform_name = os.environ.get("PLATFORM_NAME", default="")
    source_root = os.environ.get("SOURCE_ROOT", default="")
    wrapper_name = os.environ.get("WRAPPER_NAME")
    wrapper_extension = os.environ.get("WRAPPER_EXTENSION")
    if wrapper_extension is None:
        print(
            "error: Unrecognized wrapper extension, neither an app nor xctest",
            file=sys.stderr,
        )
        exit(1)
    wrapper_extension = "app"

    # Buck Environment
    buck_cell_relative_path = os.environ.get("BUCK_CELL_RELATIVE_PATH")
    build_target = os.environ.get("BUILD_TARGET").replace("\/\/", "//")
    built_products_dir = os.environ.get("BUILT_PRODUCTS_DIR", default="")
    target_name = os.environ.get("TARGET_NAME", default="")

    buck_cell_root = os.path.normpath(
        os.path.join(source_root, buck_cell_relative_path)
    )

    product_name = (
        wrapper_name
        if wrapper_name is not None and wrapper_name != ""
        else target_name + "." + wrapper_extension
    )
    path = os.path.join(built_products_dir, product_name)

    build_target += "#" + platform_name + "-" + arch
    if os.path.exists(path):
        try:
            shutil.rmtree(path)
        except OSError:
            os.unlink(path)
    command = ["buck", "build", build_target] + [
        "--report-absolute-paths",
        "--show-output",
        "--keep-going",
    ]
    print(command, file=sys.stderr)
    output = subprocess.check_output(command, encoding="utf-8")
    print(output, file=sys.stderr)
    cell_relative_path = os.path.join(
        "buck-out", output.split(" buck-out/")[-1].strip()
    )
    app = os.path.join(buck_cell_root, cell_relative_path)
    if not os.path.exists(app):
        print(
            f"error: {product_name} not found at expected path ({app}).",
            file=sys.stderr,
        )
        exit(1)

    shutil.copytree(app, path, symlinks=True)
    machine_log_path = os.path.join(
        buck_cell_root, "buck-out", "log", "last_buildcommand", "buck-machine-log"
    )
    machine_log_first_line = ""
    try:
        with open(machine_log_path, "r", encoding="utf-8") as fp:
            machine_log_first_line = fp.readline()
    except OSError:
        print("warning: Buck machine log unable to be read for run.", file=sys.stderr)
    if machine_log_first_line != "":
        machine_log_json: Dict[str, Any] = {}
        try:
            machine_log_json = machine_log_first_line.split()[1]
        except IndexError:
            print("warning: Buck machine log unable to be parsed", file=sys.stderr)
        if len(machine_log_json) > 0:
            machine_log_dict = json.loads(machine_log_json)
            build_id = machine_log_dict["buildId"]
            print(
                "To get a better view of your build including trace and cache miss "
                "information, view:\n\n"
                f"https://our.internmc.facebook.com/intern/buck/build/{build_id}\n",
                file=sys.stderr,
            )
    if wrapper_extension == "app":
        frameworks_path = os.path.join(path, "Frameworks")
        if not os.path.exists(frameworks_path):
            return
        for framework in os.listdir(frameworks_path):
            print("LOOKING AT FRAMEWORK: ", file=sys.stderr)
            print(os.path.join(frameworks_path, framework), file=sys.stderr)
            print(os.path.join(built_products_dir, framework), file=sys.stderr)
            if os.path.exists(os.path.join(built_products_dir, framework)):
                try:
                    shutil.rmtree(os.path.join(built_products_dir, framework))
                except OSError:
                    os.unlink(os.path.join(built_products_dir, framework))
            shutil.copytree(
                os.path.join(frameworks_path, framework),
                os.path.join(built_products_dir, framework),
                symlinks=True,
            )


main()
