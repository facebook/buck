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
                    expanded_args.append(arg1.replace("\n", " "))
        else:
            expanded_args.append(arg)

    # write the args to the expected files manually
    with open(
        "buck-out/gen/bin#incremental-thinlto/thinlto.indices/main.cpp.o.thinlto.bc",
        "w",
    ) as f:
        f.writelines(expanded_args)

    with open(
        "buck-out/gen/bin#incremental-thinlto/thinlto.indices/main.cpp.o.imports", "w"
    ) as f:
        f.writelines(expanded_args)


if __name__ == "__main__":
    main()
