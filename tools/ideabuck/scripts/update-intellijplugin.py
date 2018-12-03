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
import os
import subprocess
import sys
from contextlib import contextmanager

from updatecommon import (
    get_release,  # noqa ignore module level import not at top lint; noqa ignore module level import not at top lint
    upload_release,
)

sys.path.append(
    os.path.abspath(
        os.path.join(  # add the previous directory to our path
            os.path.dirname(os.path.abspath(__file__)), os.pardir
        )
    )
)


BUILT_IDEA_BUCK = (
    "../../../buck-out/gen/src/com/facebook/buck/intellij/ideabuck/ideabuck.jar"
)


@contextmanager
def os_closing(o):
    yield o
    os.close(o)


def parse_args(args):
    parser = argparse.ArgumentParser(description="Update intellij plugin")
    parser.add_argument(
        "version_tag", help="The name of the tag to create a release for"
    )
    parser.add_argument(
        "github_token",
        type=file,
        help="The authentication token to use to talk to GitHub",
    )

    parser.add_argument(
        "--buck-repo-location",
        default=os.path.dirname(os.path.abspath(__file__)),
        help="The location of the buck repository",
    )
    return parser.parse_args(args)


def build_plugin(version_tag, buck_repo_location):
    print("Building plugin...")
    subprocess.check_call(
        ["buck", "build", "//tools/ideabuck:ideabuck"], cwd=buck_repo_location
    )
    dest_name = "buck-intellij-plugin-{version_name}.jar".format(
        version_name=version_tag[1:]
    )
    subprocess.check_call(
        ["mv", os.path.join(buck_repo_location, BUILT_IDEA_BUCK), dest_name],
        cwd=buck_repo_location,
    )
    return os.path.join(buck_repo_location, dest_name)


def update_jar(version_tag, github_token, buck_repo_location):
    release_data = get_release(version_tag)

    # Now, build the plugin's binary, and update the file.
    plugin_file = build_plugin(version_tag, buck_repo_location)
    upload_release(
        plugin_file,
        release_data["upload_url"],
        github_token,
        {"Content-Type": "application/java-archive"},
    )
    os.remove(plugin_file)


if __name__ == "__main__":
    args = parse_args(sys.argv[1:])
    github_token_lines = args.github_token.readlines()
    assert len(github_token_lines) == 1, "Invalid number of lines in github_token file"
    update_jar(args.version_tag, github_token_lines[0].strip(), args.buck_repo_location)
