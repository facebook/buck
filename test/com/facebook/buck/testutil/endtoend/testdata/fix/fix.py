#!/usr/bin/env python

from __future__ import print_function

import argparse
import json
import sys


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-details", required=True)
    parser.add_argument("--exit-code", type=int, default=0)
    parser.add_argument("--wait", action="store_true")
    args = parser.parse_args()

    with open(args.build_details, "r") as fin:
        js = json.load(fin)

    if args.wait:
        user_data = sys.stdin.read().strip()
        print("user entered {}".format(user_data), file=sys.stderr)

    print("build_id: {}".format(js["build_id"]), file=sys.stderr)
    print("command: {}".format(js["command"]), file=sys.stderr)
    print("exit_code: {}".format(js["exit_code"]), file=sys.stderr)
    print(
        "jasabi_fix: {}".format(js["buck_provided_scripts"]["jasabi_fix"][0]),
        file=sys.stderr,
    )
    print("manually_invoked: {}".format(js["manually_invoked"]), file=sys.stderr)
    sys.exit(args.exit_code)
