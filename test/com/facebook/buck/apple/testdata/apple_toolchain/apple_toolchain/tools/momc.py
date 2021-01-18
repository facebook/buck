#!/usr/bin/python3

import argparse
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("source")
parser.add_argument("destination")
ns, _ = parser.parse_known_args()

Path(ns.destination).touch()
