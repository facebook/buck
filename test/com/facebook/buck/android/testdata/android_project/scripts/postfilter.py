#!/usr/bin/env python2
# Copyright 2004-present Facebook. All Rights Reserved.

from __future__ import absolute_import, division, print_function, unicode_literals

import json
import os
import sys


def main():
    build_data_path = sys.argv[1]
    rjson_path = sys.argv[2]
    with open(build_data_path) as f:
        in_to_out_map = json.load(f)["res_dir_map"]
    for outdir in in_to_out_map.values():
        tiny_black = os.path.join(outdir, "drawable", "tiny_black.png")
        if os.path.isfile(tiny_black):
            os.unlink(tiny_black)
            tiny_black_xml = os.path.join(outdir, "drawable", "tiny_black.xml")
            with open(tiny_black_xml, "w") as f:
                f.write("<xml/>")
            new_xml = os.path.join(outdir, "drawable", "tiny_new.xml")
            with open(new_xml, "w") as f:
                f.write("<xml/>")
    with open(rjson_path, "w") as f:
        json.dump(
            {
                "com.sample": [
                    "int drawable tiny_black 0x7f010001 #",
                    "int drawable tiny_new 0x7f010002 #",
                ]
            },
            f,
        )


if __name__ == "__main__":
    main()
