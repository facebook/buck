#!/usr/bin/python3

import argparse
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("source")
parser.add_argument("destination")
parser.add_argument("--sdkroot")
parser.add_argument("--module")
parser.add_argument("--iphoneos-deployment-target")
ns, _ = parser.parse_known_args()

assert Path(ns.sdkroot).is_dir()
assert (Path(ns.sdkroot) / "Frameworks").is_dir()
assert (Path(ns.sdkroot) / "testfile").exists()

Path(ns.destination).mkdir(parents=True, exist_ok=True)
