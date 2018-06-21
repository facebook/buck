from __future__ import absolute_import, division, print_function, unicode_literals

import json
import optparse
import os
from os.path import dirname, join
from zipfile import ZipFile


def main():
    parser = optparse.OptionParser()
    parser.add_option("--config")
    parser.add_option("--keep")
    parser.add_option("--keyalias")
    parser.add_option("--keypass")
    parser.add_option("--keystore")
    parser.add_option("--out")
    parser.add_option("--proguard-config")
    parser.add_option("--proguard-map")
    parser.add_option("--sign", action="store_true")
    parser.add_option("--redex-binary")
    parser.add_option("-P")
    parser.add_option("-J")
    parser.add_option("-j")
    parser.add_option("-S")

    (options, args) = parser.parse_args()

    values = {
        # Environment variables.
        "ANDROID_SDK": os.environ["ANDROID_SDK"],
        "PWD": os.environ["PWD"],
        # Flag values.
        "config": options.config,
        "keep": options.keep,
        "keyalias": options.keyalias,
        "keypass": options.keypass,
        "keystore": options.keystore,
        "out": options.out,
        "proguard-config": options.proguard_config,
        "proguard-map": options.proguard_map,
        "sign": options.sign,
        "P": options.P,
        # extra args
        "redex-binary": options.redex_binary,
        "J": options.J,
        "j": options.j,
        "S": options.S,
    }

    tmp_out = options.out + ".tmp"
    with open(tmp_out, "w") as f:
        json.dump(values, f)

    with ZipFile(options.out, "w") as zf:
        zf.write(tmp_out, arcname="app_redex")

    # The additional artifacts
    outdir = dirname(options.out)
    open(join(outdir, "redex-class-id-map.txt"), "a").close()
    open(join(outdir, "redex-method-id-map.txt"), "a").close()
    open(join(outdir, "redex-stats.txt"), "a").close()


if __name__ == "__main__":
    main()
