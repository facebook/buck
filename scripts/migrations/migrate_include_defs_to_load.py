#!/usr/bin/env python3

import argparse
import json
import logging
import os
import pathlib
import subprocess
import tempfile
from typing import Dict, List

MY_DIR = os.path.dirname(os.path.realpath(__file__))


def load_cell_roots(repo_dir: str) -> Dict[str, str]:
    """Returns a map with cell keys and their roots as values."""
    cell_config = (
        subprocess.check_output(["buck", "audit", "cell"], cwd=repo_dir)
        .decode()
        .strip()
    )
    logging.debug("Cell config: %r" % cell_config)
    cell_roots = {}  # type: Dict[str, str]
    if not cell_config:
        return cell_roots
    for config in cell_config.split(os.linesep):
        cell, path = map(lambda s: s.strip(), config.split(":"))
        cell_roots[cell] = path
    logging.debug("Loaded following cell roots: %r" % cell_roots)
    return cell_roots


def load_export_map(
    repo: str,
    cell_roots: Dict[str, str],
    build_file: str,
    cell_prefix: str = None,
    verbose: bool = False,
):
    """
    Returns a dictionary with import string keys and all symbols they export as
    values.
    """
    cell_root_args = []
    for cell, root in cell_roots.items():
        cell_root_args.extend(["--cell_root", cell + "=" + root])
    dump_script = os.path.join(MY_DIR, "dump.py")
    verbosity_flags = ["-v"] if verbose else []
    export_map_flags = ["export_map", "--use_load_function_import_string_format"]
    cell_prefix_flags = (
        ["--cell_prefix", cell_prefix] if cell_prefix is not None else []
    )
    dump_command = (
        [dump_script, "--json", "--repository", repo]
        + verbosity_flags
        + cell_root_args
        + export_map_flags
        + cell_prefix_flags
        + [build_file]
    )
    return json.loads(subprocess.check_output(dump_command).decode().strip())


class Buildozer:
    """Represents a buildozer tool."""

    def __init__(self, path: str, repo: str) -> None:
        self.path = path
        self.repo = repo

    def run(self, *commands: str) -> None:
        with tempfile.NamedTemporaryFile("w") as commands_file:
            for command in commands:
                commands_file.write(command)
                commands_file.write(os.linesep)
            commands_file.flush()
            try:
                subprocess.check_output(
                    [self.path, "-f", commands_file.name], cwd=self.repo
                )
            except subprocess.CalledProcessError as e:
                # return code 3 is returned when there are no changes, so
                # interpret it as success
                if e.returncode != 3:
                    raise


def add_load_funcs(
    buildozer: Buildozer, load_funcs: Dict[str, List[str]], package: str
) -> None:
    """Add load functions to package."""
    commands = []
    for import_string, symbols in load_funcs.items():
        commands.append(
            "new_load "
            + import_string
            + " "
            + " ".join(symbols)
            + "|"
            + package
            + ":__pkg__"
        )
    buildozer.run(*commands)


def remove_include_defs(buildozer: Buildozer, package: str) -> None:
    """Remove all include_defs functions from package."""
    buildozer.run("delete|" + package + ":%include_defs")


def fix_unused_loads(buildozer: Buildozer, package: str) -> None:
    """Remove all unused load symbols from package."""
    buildozer.run("fix unusedLoads|" + package + ":__pkg__")


def test_build_file(build_file: str, repo: str):
    """Verify that build file syntax is correct."""
    logging.debug("Testing %s...", build_file)
    subprocess.check_output(["buck", "audit", "rules", build_file], cwd=repo)


def main():
    parser = argparse.ArgumentParser(
        description="Migrates usages of include_defs function to load ."
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Enable verbose diagnostic messages.",
    )
    parser.add_argument("build_file", metavar="FILE", help="Build file path.")
    parser.add_argument(
        "--repository", metavar="DIRECTORY", required="True", help="Repository path."
    )
    parser.add_argument(
        "--buildozer", metavar="FILE", required=True, help="Buildozer path."
    )
    parser.add_argument(
        "--cell_prefix",
        default="@",
        help="The prefix to use for cells in import strings.",
    )
    parser.add_argument(
        "--test",
        action="store_true",
        help="Whether new build file should be tested in the end.",
    )
    args = parser.parse_args()
    logging_level = logging.DEBUG if args.verbose else logging.INFO
    logging.basicConfig(
        level=logging_level,
        format=("%(asctime)s [%(levelname)s][%(filename)s:%(lineno)d] %(message)s"),
    )
    cell_roots = load_cell_roots(args.repository)
    buildozer = Buildozer(args.buildozer, args.repository)
    package_dir = os.path.dirname(args.build_file)
    package = str(pathlib.Path(package_dir).relative_to(args.repository))
    load_funcs = load_export_map(
        repo=args.repository,
        cell_roots=cell_roots,
        build_file=args.build_file,
        cell_prefix=args.cell_prefix,
        verbose=args.verbose,
    )
    logging.debug(load_funcs)
    add_load_funcs(buildozer, load_funcs, package)
    remove_include_defs(buildozer, package)
    fix_unused_loads(buildozer, package)
    if args.test:
        test_build_file(args.build_file, args.repository)


if __name__ == "__main__":
    main()
