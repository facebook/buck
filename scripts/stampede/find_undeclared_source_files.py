#!/usr/bin/python
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


import argparse
import datetime
import os
import os.path
import re
import shutil
import subprocess
import sys
import tempfile
import time


############################################################
# Classes
############################################################
class Log(object):
    """Pretty print to the console."""

    BLUE = "\033[1;34m"
    GREEN = "\033[0;32m"
    RED = "\033[1;31m"
    RESET = "\033[0;0m"
    YELLOW = "\033[0;33m"
    MAGENTA = "\033[0;35m"

    @staticmethod
    def info(msg):
        Log._print(Log.BLUE, msg)

    @staticmethod
    def warn(msg):
        Log._print(Log.YELLOW, msg)

    @staticmethod
    def highlight(msg):
        Log._print(Log.MAGENTA, msg)

    @staticmethod
    def success(msg):
        Log._print(Log.GREEN, msg)

    @staticmethod
    def error(msg):
        Log._print(Log.RED, msg)

    @staticmethod
    def _print(color, msg):
        # More complete ts: '%Y-%m-%d %H:%M:%S'
        ts = datetime.datetime.now().strftime("%H:%M:%S")
        print("{}[{}] {}{}".format(color, ts, msg, Log.RESET))


class Paths(object):
    """All the output paths used in this script."""

    def __init__(self, root_dir):
        if not os.path.isdir(root_dir):
            Log.info("Creating root dir [{}].".format(root_dir))
            os.makedirs(root_dir)
        self.root_dir = root_dir
        self.stampede_raw_file = os.path.join(self.root_dir, "stampede.raw.log")
        self.stampede_processed_file = os.path.join(
            self.root_dir, "stampede.processed.log"
        )
        self.strace_raw_file = os.path.join(self.root_dir, "strace.raw.log")
        self.strace_processed_file = os.path.join(self.root_dir, "strace.processed.log")
        self.summary_file = os.path.join(self.root_dir, "summary.log")

    def print_info(self):
        Log.highlight("Using the following paths:")
        Log.highlight("  root_dir=[{}]".format(self.root_dir))
        Log.highlight("  stampede_raw=[{}]".format(self.stampede_raw_file))
        Log.highlight("  strace_raw=[{}]".format(self.strace_raw_file))
        Log.highlight("  stampede_processed=[{}]".format(self.stampede_raw_file))
        Log.highlight("  strace_processed=[{}]".format(self.strace_raw_file))
        Log.highlight("  summary=[{}]".format(self.summary_file))


############################################################
# Functions
############################################################
def parse_args():
    parser = argparse.ArgumentParser(
        description="Finds untracked source files in BUCK files."
    )
    parser.add_argument(
        "-d",
        "--root_dir",
        dest="root_dir",
        default=os.path.join(
            os.path.expanduser("~"), "tmp/find_undeclared_source_files"
        ),
        help="Root dir where all files will be created.",
    )
    parser.add_argument(
        "build_targets", nargs="+", help="Buck build targets to analyse."
    )
    parser.add_argument(
        "-m",
        "--mode",
        dest="modes",
        action="append",
        choices=[
            "all",
            "run_strace",
            "run_stampede",
            "process_strace",
            "process_stampede",
            "summarise",
        ],
        default=None,
        help="Mode to run this script in.",
    )
    parser.add_argument(
        "-i",
        "--include",
        dest="includes",
        action="append",
        help=(
            "If any include dir prefix is passed, then the processed output "
            "file will **only** contain paths that start with any of the "
            "provided prefixes."
        ),
    )
    parser.add_argument(
        "-k",
        "--known_root",
        dest="known_roots",
        action="append",
        help="Known root directories to resolve strace relative paths.",
    )
    args = parser.parse_args()
    if args.includes:
        args.includes = [os.path.realpath(i) for i in args.includes]
    if not args.modes:
        args.modes = ["all"]
    if args.known_roots is None:
        args.known_roots = []
    args.known_roots.insert(0, os.getcwd())
    args.known_roots = unique_list([os.path.realpath(p) for p in args.known_roots])
    return args


def unique_list(src_list):
    """Return unique elements of the list maintaining the original order."""
    dst_list = []
    all_elements = set()
    for e in src_list:
        if e not in all_elements:
            all_elements.add(e)
            dst_list.append(e)
    return dst_list


def run_cmd(cmd):
    """Run a command on the bash. Raise an exception on error."""
    Log.info("Running the following command:")
    Log.info("  {0}".format(cmd))
    try:
        subprocess.check_call(cmd, shell=True)
        Log.success("Command finished successfully with code 0.")
    except subprocess.CalledProcessError as e:
        error = "Failed to run command with exit code [{}]. cmd=[{}]".format(
            e.returncode, e.cmd
        )
        Log.error(error)
        raise e


def run_strace(out_file, build_targets):
    """Run a buck build wrapped with strace."""
    assert type(build_targets) == list
    buck_cmd = (
        "buck build {build_targets} "
        "--no-cache "
        "--config cache.mode=dir "
        "--config cache.slb_server_pool="
    ).format(build_targets=" ".join(build_targets))
    cmd = "strace -f -e open -o {out_file} {buck_cmd}".format(
        out_file=out_file, buck_cmd=buck_cmd
    )
    run_cmd(cmd)


def run_stampede(out_file, build_targets):
    """Run stampede build outputing the required source dependencies."""
    assert type(build_targets) == list
    cells_root_path = os.path.abspath(os.path.dirname(os.getcwd()))
    cmd = (
        "buck distbuild sourcefiles "
        "--cells-root-path {cells_root_path} "
        "--output-file {stampede_out} "
        "--no-cache "
        "--config cache.mode= "
        "--config cache.slb_server_pool= {build_targets}"
    ).format(
        stampede_out=out_file,
        build_targets=" ".join(build_targets),
        cells_root_path=cells_root_path,
    )
    run_cmd(cmd)


def process_stampede(raw_file, processed_file, includes):
    """Process and sanitise the stampede output file."""
    Log.info("Processing file [{}].".format(raw_file))
    all_lines = set()
    with open(raw_file) as fp:
        for line in fp:
            path = os.path.realpath(line.strip())
            if is_valid_path(path, includes):
                all_lines.add(path + "\n")
    with open(processed_file, "w") as fp:
        fp.writelines(sorted(all_lines))
    Log.success(
        "[{}] lines were written into [{}].".format(len(all_lines), processed_file)
    )


def process_strace(raw_file, processed_file, includes, known_roots):
    Log.info("Processing file [{}].".format(raw_file))
    pattern = re.compile(r'open\("([^"]+)"')
    all_lines = set()
    with open(raw_file) as fp:
        for line in fp:
            match = pattern.search(line)
            if match:
                path = match.group(1)
                if not os.path.isabs(path):
                    for root in known_roots:
                        real_path = os.path.realpath(os.path.join(root, path))
                        if is_valid_path(real_path, includes):
                            all_lines.add(real_path + "\n")
                else:
                    real_path = os.path.realpath(path)
                    if is_valid_path(real_path, includes):
                        all_lines.add(real_path + "\n")
    with open(processed_file, "w") as fp:
        fp.writelines(sorted(all_lines))
    Log.success(
        "[{}] lines were written into [{}].".format(len(all_lines), processed_file)
    )


def should_run(current_mode, modes_arg):
    if "all" in modes_arg:
        return True
    if current_mode in modes_arg:
        return True
    return False


def is_valid_path(abs_path, includes):
    if not os.path.exists(abs_path) or os.path.isdir(abs_path):
        return False
    # We don't care about:
    #   - hidden directories.
    #   - buck-out.
    #   - BUCK files.
    #   - TARGETS files.
    #   - DEFS files.
    if (
        "/." in abs_path
        or "/buck-out/" in abs_path
        or abs_path.endswith("BUCK")
        or abs_path.endswith("TARGETS")
        or abs_path.endswith("DEFS")
    ):
        return False
    if includes:
        for include in includes:
            if abs_path.startswith(include):
                return True
    else:
        return True
    return False


def read_as_set(path):
    with open(path) as fp:
        return set(fp.readlines())


def summarise(stampede, strace, summary):
    Log.info("Summarising missing files into [{}].".format(summary))
    stampede_lines = read_as_set(stampede)
    strace_lines = read_as_set(strace)
    missing_lines = strace_lines.difference(stampede_lines)
    by_extension = {}
    for line in missing_lines:
        extension = os.path.splitext(line)[1].strip()
        if extension in by_extension:
            by_extension[extension] += 1
        else:
            by_extension[extension] = 1
    with open(summary, "w") as fp:
        fp.write("Total Missing Dependencies: {}\n".format(len(missing_lines)))
        fp.write("\n")

        fp.write("== Missing Dependencies by File Extension ==\n")

        def cmp_by_count(x, y):
            return cmp(by_extension[y], by_extension[x])

        for extension in sorted(by_extension.keys(), cmp_by_count):
            ext = extension if len(extension) > 0 else "(NO_EXTENSION)"
            fp.write("{:<15}: {}\n".format(ext, by_extension[extension]))
        fp.write("\n")

        fp.write("== All Missing Dependencies ==\n")
        fp.writelines(sorted(missing_lines))


def confirm_at_repo_root():
    buckconfig_path = os.path.join(os.getcwd(), ".buckconfig")
    if not os.path.isfile(buckconfig_path):
        msg = (
            "This script must be run from the repository root."
            " Could not find [.buckconfig] in CWD [{}]."
        ).format(os.getcwd())
        Log.error(msg)
        raise Exception(msg)


############################################################
# Main
############################################################
def main():
    args = parse_args()
    paths = Paths(args.root_dir)
    paths.print_info()
    confirm_at_repo_root()
    if should_run("run_stampede", args.modes):
        run_stampede(paths.stampede_raw_file, args.build_targets)
    if should_run("run_strace", args.modes):
        run_strace(paths.strace_raw_file, args.build_targets)
    if should_run("process_stampede", args.modes):
        process_stampede(
            paths.stampede_raw_file, paths.stampede_processed_file, args.includes
        )
    if should_run("process_strace", args.modes):
        process_strace(
            paths.strace_raw_file,
            paths.strace_processed_file,
            args.includes,
            args.known_roots,
        )
    if should_run("summarise", args.modes):
        Log.info("Summarising...")
        summarise(
            paths.stampede_processed_file,
            paths.strace_processed_file,
            paths.summary_file,
        )
    Log.success("Finished finding undeclared source files successfully.")


if __name__ == "__main__":
    main()
