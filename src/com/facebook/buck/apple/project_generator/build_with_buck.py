#!/usr/bin/python

#  This script is a bridge between Xcode and Buck.
#  It uses the following environment variables to determine the expected behaviour:
#
#  PLATFORM_NAME - to detect cxx_platform of the build.
#                  Expected values from Xcode are: macosx, iphoneos, iphonesimulator, etc.
#                  cxx_platform is set via flavor.
#
#  ARCHS         - to detect the architectures to build for.
#                  Expected values from Xcode are: i386, x86_64, armv7, arm64, etc.
#                  Single value (eg i386) will result into thin binary.
#                  Multiple values (eg "armv7 arm64") will result into fat binary.
#
#  VALID_ARCHS   - to determine what values of ARCHS are valid and what are not.
#                  The final build architectures value is the result of intersection
#                  of ARCHS and VALID_ARCHS.
#
#  DEBUG_INFORMATION_FORMAT - to detect the apple_debug_format of the build.
#                             Expected values from Xcode are: dwarf, dwarf-with-dsym
#                             The debug format is set via flavor.

import os
import sys
import subprocess

XCODE_DWARF_DEBUG_INFORMATION_FORMAT = "dwarf"
XCODE_DWARF_WITH_DSYM_DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym"


def parse_args():
    import argparse
    description = """Produces the command that should be used to build with Buck from Xcode."""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument(
        'repo_root',
        help='Path to the root of the repo, e.g. /path')
    parser.add_argument(
        'buck_path',
        help='Path to buck binary/pex, e.g. /path/to/buck')
    parser.add_argument(
        'build_flags',
        help='Additional flags to pass to buck build command')
    parser.add_argument(
        'escaped_build_target',
        help='Buck target to build, e.g. //My:AppTarget')
    parser.add_argument(
        'buck_dwarf_flavor',
        help='The value of the DWARF buck flavor')
    parser.add_argument(
        'buck_dsym_flavor',
        help='The value of the DWARF_AND_DSYM buck flavor')
    return parser.parse_args()


def get_archs_for_build():
    ENV_ARCHS = os.environ['ARCHS']
    ENV_VALID_ARCHS = os.environ['VALID_ARCHS']
    requested_archs = set(ENV_ARCHS.split(" "))
    valid_archs = set(ENV_VALID_ARCHS.split(" "))
    result = requested_archs.intersection(valid_archs)
    if len(result) == 0:
        raise ValueError("ARCHS does not intersect with VALID_ARCHS")
    else:
        return result


def get_apple_debug_format_flavor(dwarf_flavor, dsym_flavor):
    ENV_DEBUG_INFORMATION_FORMAT = os.environ['DEBUG_INFORMATION_FORMAT']
    if ENV_DEBUG_INFORMATION_FORMAT.lower() == XCODE_DWARF_DEBUG_INFORMATION_FORMAT:
        return dwarf_flavor
    elif ENV_DEBUG_INFORMATION_FORMAT.lower() == XCODE_DWARF_WITH_DSYM_DEBUG_INFORMATION_FORMAT:
        return dsym_flavor
    else:
        raise ValueError("Unknown debug format: " + ENV_DEBUG_INFORMATION_FORMAT)


def get_cxx_platform_flavors():
    platform = os.environ['PLATFORM_NAME']
    archs = get_archs_for_build()
    flavors = set()
    for arch in archs:
        flavor = platform + "-" + arch
        flavors.add(flavor)
    return flavors


def get_all_flavors_as_string(buck_dwarf_flavor, buck_dsym_flavor):
    flavors = get_cxx_platform_flavors()
    flavors.add(get_apple_debug_format_flavor(buck_dwarf_flavor, buck_dsym_flavor))
    return ",".join(sorted(flavors))


def get_command(repo_root, buck_path, flags, build_target, buck_dwarf_flavor, buck_dsym_flavor):
    command = buck_path + " build " + flags + " " + build_target
    command += "#" + get_all_flavors_as_string(buck_dwarf_flavor, buck_dsym_flavor)
    return command


def main():
    args = parse_args()
    command = get_command(args.repo_root,
                          args.buck_path,
                          args.build_flags,
                          args.escaped_build_target,
                          args.buck_dwarf_flavor,
                          args.buck_dsym_flavor)
    print command


if __name__ == '__main__':
    main()
