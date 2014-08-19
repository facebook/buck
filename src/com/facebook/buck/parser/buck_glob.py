#!/usr/bin/env python
import optparse
import sys

from buck import glob


def main():
    parser = optparse.OptionParser(
        description="Walk the path hierarchy from the given path origin(s), "
                    "including and excluding certain patterns (example: "
                    "'src/**/foo/*.java)")
    parser.add_option(
        "-i",
        "--include",
        help="include pattern",
        nargs='+')
    parser.add_option(
        "-e",
        "--exclude",
        help="exclude pattern",
        nargs='*',
        default=[])
    parser.add_option(
        "-a",
        "--all",
        help=("While expanding patterns, do not skip files or directories "
              "starting with a dot"),
        action='store_true',
        default=False)
    opts, args = parser.parse_args()
    origins = args if args else ['']
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
