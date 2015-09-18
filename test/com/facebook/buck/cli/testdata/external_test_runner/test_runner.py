#!/usr/bin/python -u

import json
import sys
import subprocess


for path in sys.argv[1:]:
    with open(path) as f:
        info = json.load(f)
    subprocess.call(info['command'])
