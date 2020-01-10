#!/usr/bin/env python3.7
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

import argparse
import re
import subprocess
import sys
import textwrap
from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
from typing import *
from typing import Match, Pattern

GIT_TEMPLATE = ""


@dataclass(eq=True, frozen=True)
class Option:
    section: str
    name: str
    raw_contents: str

    def __str__(self) -> str:
        return f"{self.section}.{self.name}"

    def clean_name(self) -> str:
        return "{}.{}".format(self.section, re.sub("\\W", "", self.name).strip("_"))


@dataclass
class Section:
    name: str
    header: str
    options: List[Option]


@dataclass
class Config:
    prefix: str
    sections: List[Section]
    suffix: str


# Coincidentally, ^{/call} is the last entry in the file. There's a {/call} later, but
# it is indented.
main_re = re.compile(
    "(?P<prefix>.*?)(?P<body>{call buckconfig.section}.*^{/call})\n+(?P<suffix>.*)",
    re.MULTILINE | re.DOTALL,
)
section_re = re.compile(
    r"""(?P<header>{call buckconfig.section}
\s*{param name: '(?P<name>\S+)' /}
\s*{param description}.*?
\s*{/param}
\s*{/call})(?P<inner>.*?)({call buckconfig.section}|\Z)""",
    re.MULTILINE | re.DOTALL,
)
option_re = re.compile(
    r"""(?P<raw>^{call buckconfig.entry}.*?{param section: '(?P<section>.+?)' /}.*?{param name: '(?P<name>.+?)' /}.*?^{/call})""",
    re.MULTILINE | re.DOTALL,
)


def overlapping_iter(
    regex: Pattern[str], data: str, group: str
) -> Generator[Match[str], None, None]:
    """ Regex iterator that allows us to overlap a bit with other matches """
    start_idx = 0
    while start_idx < len(data):
        match = regex.match(data, start_idx)
        if not match:
            return
        yield match
        start_idx = match.end(group)


def get_options(path: Path) -> Config:
    with open(path, "r") as fin:
        data = fin.read()
    global_match = main_re.match(data)
    if not global_match:
        raise ValueError("Could not match regex on file")
    prefix = global_match.group("prefix")
    suffix = global_match.group("suffix")

    sections = []
    for section_match in overlapping_iter(
        section_re, global_match.group("body"), "inner"
    ):
        options = []
        for option_match in option_re.finditer(section_match.group("inner")):
            options.append(
                Option(
                    option_match.group("section"),
                    option_match.group("name"),
                    option_match.group("raw"),
                )
            )
        sections.append(
            Section(section_match.group("name"), section_match.group("header"), options)
        )
        suffix_idx = section_match.end("inner") + 1
    return Config(prefix, sections, suffix)


def sort_one_option(config: Config, exclude: List[str]) -> Optional[Option]:
    """ Here it is, an in the wild usecase for a bubble sort """
    changed_option = None
    for section in config.sections:
        for i in range(len(section.options) - 1):
            curr_opt = section.options[i]
            next_opt = section.options[i + 1]
            full_name = str(curr_opt)
            if curr_opt.name > next_opt.name and full_name not in exclude:
                for j in range(i, len(section.options) - 1):
                    if section.options[j].name > section.options[j + 1].name:
                        section.options[j + 1], section.options[j] = (
                            section.options[j],
                            section.options[j + 1],
                        )
                        changed_option = section.options[j]
                    else:
                        break
                return changed_option

    return changed_option


def write_config(config: Config, dest: Path) -> None:
    with open(dest, "w") as fout:
        fout.write(config.prefix)
        for section in config.sections:
            fout.write(section.header)
            for option in section.options:
                fout.write("\n\n")
                fout.write(option.raw_contents)
            fout.write("\n\n")
        fout.write(config.suffix)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "exclude",
        nargs="*",
        help="List of <section.option> to ignore when alphabetizing",
    )
    parser.add_argument(
        "--list", action="store_true", help="Just list available options"
    )
    parser.add_argument("--no-commit", action="store_false", dest="commit")
    parser.add_argument("--branch-template", default="reorder_docs_{option_name}")
    parser.add_argument("--commit-template-file")
    parser.add_argument(
        "--buckconfig-docs-path",
        type=Path,
        default=Path("docs/files-and-dirs/buckconfig.soy"),
    )
    parser.add_argument(
        "--dest", type=Path, default=Path("docs/files-and-dirs/buckconfig.soy")
    )
    args = parser.parse_args()
    if args.commit and not args.commit_template_file:
        parser.error(
            "\n"
            + textwrap.fill(
                "commit requested, but --commit-template-file was not specified. "
                "Either specify the file, or run with --no-commit",
                80,
            )
        )
    return args


def git_is_clean() -> bool:
    ret = subprocess.run(["git", "diff", "--quiet"])
    if ret.returncode == 0:
        return True
    elif ret.returncode == 1:
        return False
    else:
        ret.check_returncode()


def accept_git_changes() -> bool:
    subprocess.run(["git", "diff"])
    print(
        "Commit previously shown changes? A new branch will be created, and summary "
        "automatically added [y/N] ",
        end="",
    )
    if input().lower().strip() == "y":
        return True
    else:
        print("Not creating commit. Changes are present in your working directory")
        return False


def create_branch(branch_template, changed_option) -> None:
    branch_name = branch_template.format(option_name=changed_option.clean_name())
    subprocess.run(["git", "branch", "--track", branch_name], check=True)
    subprocess.run(["git", "checkout", branch_name], check=True)


def create_commit(commit_template_file, changed_option) -> None:
    with open(commit_template_file, "r") as fin:
        commit_template = fin.read()
    commit_message = commit_template.format(option_name=changed_option.clean_name())
    subprocess.run(
        ["git", "commit", "-a", "-F", "-"],
        input=commit_message,
        encoding="utf8",
        check=True,
    )


def main() -> int:
    args = parse_args()
    config = get_options(args.buckconfig_docs_path)

    if args.list:
        for section in config.sections:
            for option in section.options:
                print(f"{option.section}.{option.name}")
        return 0

    if args.commit and not git_is_clean():
        print(
            "In order to commit, you must have a clean repository to start with. "
            "(run with --no-commit to skip automatically committing)"
        )
        return 1

    changed_option = sort_one_option(config, args.exclude)

    write_config(config, args.dest)
    if changed_option and args.commit:
        if accept_git_changes():
            create_branch(args.branch_template, changed_option)
            create_commit(args.commit_template_file, changed_option)
        else:
            return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
