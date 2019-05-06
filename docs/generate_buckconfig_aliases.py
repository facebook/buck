#!/usr/bin/env python3
# Copyright 2019-present Facebook, Inc.
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
import re
import sys
from collections import defaultdict

SECTION_REGEX = re.compile(
    r"{call buckconfig.section}\s*{param name: '(?P<section>.+?)' /}",
    re.DOTALL | re.MULTILINE,
)
OPTION_REGEX = re.compile(
    r"{call buckconfig.entry}.*?{param section: '(?P<section>.+?)' /}.*?{param name: '(?P<name>.+?)' /}",
    re.DOTALL | re.MULTILINE,
)
GENERATED_REGEX = re.compile(
    r"/\*\*\* GENERATED \*\*\*/.*?/\*\*\* END GENERATED \*\*\*/",
    re.DOTALL | re.MULTILINE,
)

SECTION = """
/***/
{{template .{section}}}
{{call .section_link}}
  {{param section: '{section}' /}}
{{/call}}
{{/template}}

"""

OPTION = """
/***/
{{template .{section}_{option}}}
{{call .entry_link}}
  {{param section: '{section}' /}}
  {{param entry: '{option}' /}}
{{/call}}
{{/template}}

"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--buckconfig", default="files-and-dirs/buckconfig.soy")
    parser.add_argument("--buckconfig-aliases", default="__buckconfig_common.soy")
    parser.add_argument("--buckconfig-aliases-dest", default="__buckconfig_common.soy")
    args = parser.parse_args()

    sections = set()
    options = defaultdict(set)
    with open(args.buckconfig, "r") as fin:
        data = fin.read()
        for match in SECTION_REGEX.finditer(data):
            sections.add(match.group("section"))
        for match in OPTION_REGEX.finditer(data):
            name = re.sub(r"\W", "", match.group("name")).strip("_")
            options[match.group("section")].add(name)

    with open(args.buckconfig_aliases, "r") as fin:
        data = fin.read()
        replacement = "/*** GENERATED ***/"
        for section in sorted(sections):
            replacement += SECTION.format(section=section)
        for section in sorted(options.keys()):
            for option in sorted(options[section]):
                replacement += OPTION.format(section=section, option=option)

        replacement += "/*** END GENERATED ***/"
        data = GENERATED_REGEX.sub(replacement, data)

    with open(args.buckconfig_aliases_dest, "w") as fout:
        fout.write(data)
    return 0


if __name__ == "__main__":
    sys.exit(main())
