#!/usr/bin/env python2
# Copyright 2004-present Facebook. All Rights Reserved.

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import unicode_literals

import json
import os
import sys

def main():
    build_data_path = sys.argv[1]
    with open(build_data_path) as f:
        in_to_out_map = json.load(f)['res_dir_map']
    for outdir in in_to_out_map.values():
        for root, dirs, files in os.walk(outdir):
            for name in files:
                if not 'app_icon' in name:
                    path = os.path.join(root, name)
                    newpath = os.path.join(root, 'test_' + name)
                    os.rename(path, newpath)

if __name__ == '__main__':
  main()
