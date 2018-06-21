# Copyright 2018-present, Facebook, Inc.
# All rights reserved.
#
# This source code is licensed under the license found in the
# LICENSE file in the root directory of this source tree.

import argparse
import os
import subprocess


def parse_args():
    parser = argparse.ArgumentParser(
        description="Generates java file that outputs given android target"
    )
    parser.add_argument("target", type=str, help="String to have java file output")
    parser.add_argument(
        "tmp", type=str, help="TMP directory to store intermediate information"
    )
    parser.add_argument("output", type=str, help="Where to save output jar")
    return (
        parser.parse_args().target,
        parser.parse_args().tmp,
        parser.parse_args().output,
    )


def generate_java(target, tmp, output):
    with open(os.path.join(tmp, "AndroidTarget.java"), "w") as f:
        f.write(
            """package com.facebook.buck.demo.target;

public class AndroidTarget {{
    public static String getTarget() {{
        return "{}";
    }}
}}""".format(
                target
            )
        )
    os.makedirs(os.path.join(tmp, "classes"))
    subprocess.check_call(
        [
            "javac",
            "-source",
            "1.7",
            "-target",
            "1.7",
            "-d",
            "classes",
            "AndroidTarget.java",
        ],
        cwd=tmp,
    )
    subprocess.check_call(["jar", "-cf", output, "-C", "classes", "."], cwd=tmp)


if __name__ == "__main__":
    target, tmp, output = parse_args()
    generate_java(target, tmp, output)
