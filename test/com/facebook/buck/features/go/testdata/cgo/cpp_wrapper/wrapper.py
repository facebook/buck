#!/usr/bin/env python3
import argparse
import subprocess
import sys


parser = argparse.ArgumentParser(add_help=False)
parser.add_argument("--cc", required=True)
parsed, args = parser.parse_known_args(sys.argv[1:])
cc_prog = parsed.cc

assert args, "no arguments other than --cc were passed"

sys.exit(subprocess.run([cc_prog] + args).returncode)
