#!/usr/bin/env python2.7

import json
import os
import re
import sys
from pprint import pformat, pprint


"""
This is an external test runner for integration tests that checks the command arguments passed
to external test runners. Right now it checks the following arguments:

- "-Dbuck.robolectric_res_directories", which should contain either a list of pathsep-separated
  resource folders or the name of a file that contains a list of folders with resources (in this
  case the value starts with @).

- "-Dbuck.robolectric_assets_directories", which should contain either a list of pathsep-separated
  asset folders or the name of a file that contains a list of folders with assets (in this
  case the value starts with @).
"""


def read_command_arguments_from_spec_file(spec_file):
    with open(spec_file) as data_file:
        data = json.load(data_file)

    return data[0]["command"]


def get_robolectric_res_directories(command_args):
    return find_key_in_command_args(command_args, "buck.robolectric_res_directories")


def get_robolectric_asset_directories(command_args):
    return find_key_in_command_args(command_args, "buck.robolectric_assets_directories")


def find_key_in_command_args(command_args, key):
    p = re.compile("-D" + key + "=(.*)")
    for arg in command_args:
        m = p.search(arg)
        if m:
            return m.group(1)
    raise Exception("Cannot find " + key + " in command arguments")


def get_directories_from_arg(arg):
    if arg[0] == "@":
        return open(arg[1:], "r").read().split("\n")
    else:
        return arg.split(os.pathsep)


def assert_directories_match(expected_directories, directories_arg):
    actual_directories = sorted(get_directories_from_arg(directories_arg))
    if expected_directories != actual_directories:
        print(
            "Directories do not match. "
            "Expected: "
            + pformat(expected_directories)
            + ". Actual: "
            + pformat(actual_directories)
        )
        sys.exit(1)


def assert_res_directories_match(command_args, expected_res_directories):
    res_directories = get_robolectric_res_directories(command_args)
    assert_directories_match(expected_res_directories, res_directories)


def assert_asset_directories_match(command_args, expected_asset_directories):
    asset_directories = get_robolectric_asset_directories(command_args)
    assert_directories_match(expected_asset_directories, asset_directories)


def main(argv):
    command_args = read_command_arguments_from_spec_file(argv[2])

    assert_res_directories_match(
        command_args,
        [
            "buck-out/gen/res/com/sample/base/base#resources-symlink-tree/res",
            "buck-out/gen/res/com/sample/title/title#resources-symlink-tree/res",
            "buck-out/gen/res/com/sample/top/top#resources-symlink-tree/res",
        ],
    )

    assert_asset_directories_match(
        command_args,
        ["buck-out/gen/res/com/sample/base/base#assets-symlink-tree/assets"],
    )


if __name__ == "__main__":
    main(sys.argv)
