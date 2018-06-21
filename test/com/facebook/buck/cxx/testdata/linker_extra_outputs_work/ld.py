#!/usr/bin/env python
"""A fake ld that can emit extra output."""

import argparse
import sys


def main():
    # expand argfiles.
    expanded_args = []
    for arg in sys.argv:
        if arg.startswith("@"):
            with open(arg[1:], "rb") as f:
                for arg1 in f:
                    expanded_args.append(arg1.rstrip("\n").strip('"'))
        else:
            expanded_args.append(arg)

    # parse args.
    print(expanded_args)
    parser = argparse.ArgumentParser()
    parser.add_argument("-o", dest="output", help="Main output", required=True)
    parser.add_argument("--extra-output", dest="extra_output", required=True)
    ns, _unknown = parser.parse_known_args(expanded_args)

    # write the output path into the output file.
    with open(ns.output, "wb") as f:
        f.write(ns.output)

    with open(ns.extra_output, "wb") as f:
        f.write(ns.extra_output)


if __name__ == "__main__":
    main()
