#!/usr/bin/env python
import argparse
import sys

from buck import glob


def main():
    parser = argparse.ArgumentParser(
        description="Walk the path hierarchy from the given path origin(s), "
                    "including and excluding certain patterns (example: "
                    "'src/**/foo/*.java)")
    parser.add_argument(
        "-i",
        "--include",
        help="include pattern",
        nargs='+',
        required=True)
    parser.add_argument(
        "-e",
        "--exclude",
        help="exclude pattern",
        nargs='*',
        default=[])
    parser.add_argument(
        "-a",
        "--all",
        help=("While expanding patterns, do not skip files or directories "
              "starting with a dot"),
        action='store_true',
        default=False)
    parser.add_argument(
        "origins",
        help="path origin(s)",
        nargs='*')
    opts = parser.parse_args()
    origins = opts.origins if opts.origins else ['']
    for origin in origins:
        env = {'BUILD_FILE_DIRECTORY' : origin}
        for path in glob(
                opts.include,
                opts.exclude,
                include_dotfiles=opts.all,
                build_env=env):
            print path


if __name__ == "__main__":
    main()
