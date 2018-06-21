# Copyright 2018-present, Facebook, Inc.
# All rights reserved.
#
# This source code is licensed under the license found in the
# LICENSE file in the root directory of this source tree.

import argparse


def parse_file_name():
    parser = argparse.ArgumentParser(description="Outputs given file.")
    parser.add_argument("file_name", type=str, help="Name of file to output")
    return parser.parse_args().file_name


def output_file(file_name):
    with open(file_name, mode="r") as f:
        print(f.read())


if __name__ == "__main__":
    output_file(parse_file_name())
