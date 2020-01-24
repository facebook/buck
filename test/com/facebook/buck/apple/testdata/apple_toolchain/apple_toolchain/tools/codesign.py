#!/usr/bin/env python3

import os
import sys

from tools import impl


parser = impl.argparser()

parser.add_argument("--force", action="store_true")
parser.add_argument("--sign", dest="sign")
parser.add_argument("--entitlements", dest="entitlement", action=impl.StripQuotesAction)

(options, args) = parser.parse_known_args()

assert len(args) == 1
assert os.path.exists(options.entitlement)
assert options.sign

app_folder = args[0]
output_path = os.path.join(app_folder, "app_signed")

with open(output_path, "w") as output:
    output.write("signed by codesign\n")

sys.exit(0)
