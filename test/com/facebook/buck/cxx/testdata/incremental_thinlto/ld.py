#!/usr/bin/env python
"""A fake ld that can emit extra output."""

from __future__ import print_function

import argparse
import sys


def main():
    # expand argfiles.
    expanded_args = []
    for arg in sys.argv:
        if arg.startswith("@"):
            with open(arg[1:], "rb") as f:
                for arg1 in f:
                    expanded_args.append(arg1.decode("utf-8").replace("\n", " "))
        else:
            expanded_args.append(arg)

    expanded_expanded_args = [
        line.replace("'", "") for arg in expanded_args for line in arg.split(" ")
    ]

    # Find the indices path arg, something like
    # `buck-out/gen/bin#incremental-thinlto,thinindex/thinlto.indices`
    thinlto_args = [
        line.replace("thinlto-prefix-replace=;", "")
        for line in expanded_expanded_args
        if line.endswith("/thinlto.indices")
    ]

    if len(thinlto_args) != 1:
        raise Exception(
            "cannot find thinlto.indices arg: {}".format(expanded_expanded_args)
        )

    indices, = thinlto_args

    # write the args to the expected files manually
    with open(indices + "/main.cpp.o.thinlto.bc", "w") as f:
        f.writelines(expanded_args)

    with open(indices + "/main.cpp.o.imports", "w") as f:
        f.writelines(expanded_args)


if __name__ == "__main__":
    main()
