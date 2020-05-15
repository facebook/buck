#!/usr/bin/env python3

import argparse
import json
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-path")
    parser.add_argument("--command-file")
    args = parser.parse_args()

    with open(args.command_file) as command_file:
        args = [line.strip() for line in command_file.readlines()]
        args += ["-Xclang", "-dump-tokens"]
        clang_result = subprocess.run(
            args,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            encoding=sys.stdout.encoding,
        )
        tokens = clang_result.stderr
        print(json.dumps({"token_count": tokens.count("\n"), "compiler_args": args}))
        sys.exit(0)


if __name__ == "__main__":
    main()
