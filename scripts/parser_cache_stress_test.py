#!/usr/bin/python

import mmap
import os
import re
import string
import subprocess
import sys


def parseArgs():
    import argparse

    description = """This script performs the following steps:
    [1] buck kill && buck targets --json  - to make sure we preheat graph cache
    [2] change revision to N commits up   - only HG supported
    [3] buck targets --json               - cache will be kept/dropped
    [4] buck kill && buck targets --json  - without cache
    [5] compares the output of steps [3] and [4], they expected to match"""
    parser = argparse.ArgumentParser(
        description=description, formatter_class=argparse.RawTextHelpFormatter
    )
    parser.add_argument("buck_path", help="Path to Buck")
    parser.add_argument("repo_root", help="Path to HG repo")
    parser.add_argument(
        "target_to_test", help="Buck targets to test, i.e. //apps/myapp:"
    )
    parser.add_argument("iteration_count", help="Number of iterations to run")
    parser.add_argument("stepping", help="Number of commits to jump in each iteration")
    return parser.parse_args()


def invoke(repo_path, command_as_list):
    print("Invoking: " + str(command_as_list))
    pipe = subprocess.PIPE
    process = subprocess.Popen(command_as_list, stdout=pipe, stderr=pipe, cwd=repo_path)
    out, err = process.communicate()
    exit_code = process.wait()
    if exit_code != 0:
        sys.exit(
            "Failed to invoke command: "
            + str(command_as_list)
            + "\nstderr:\n"
            + str(err)
        )
    return out


def change_revision(repo_path, stepping):
    invoke(repo_path, ["hg", "up", "-C", ".~" + str(stepping)])


def run_iteration(repo_root, buck, targets, stepping):
    revision = invoke(repo_root, ["hg", "id", "-i"])
    print("Current revision: " + revision.strip())
    # [1]
    invoke(repo_root, [buck, "kill"])
    invoke(repo_root, [buck, "targets", targets, "--json"])
    # [2]
    change_revision(repo_root, stepping)
    # [3]
    out3 = invoke(repo_root, [buck, "targets", targets, "--json"])
    # [4]
    invoke(repo_root, [buck, "kill"])
    out4 = invoke(repo_root, [buck, "targets", targets, "--json"])
    # [5]
    if out3 == out4:
        print("OUTPUTS MATCH! SUCCESS!")
        print("Output:\n" + str(out3) + "\n\n\n")
    else:
        print("Outputs are different.")
        print("Step [3] output:\n" + str(out3) + "\n\n")
        print("Step [4] output:\n" + str(out4) + "\n\n")
        sys.exit("Terminating.")


def main():
    args = parseArgs()
    for i in range(0, int(args.iteration_count)):
        print("Iteration " + str(i))
        run_iteration(
            args.repo_root, args.buck_path, args.target_to_test, int(args.stepping)
        )


if __name__ == "__main__":
    main()
