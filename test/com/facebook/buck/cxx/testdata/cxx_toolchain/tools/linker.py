#!/usr/bin/env python3

import os
import sys

from tools import impl


parser = impl.argparser()

parser.add_argument("-o", dest="output", action=impl.StripQuotesAction)
parser.add_argument("-test-arg", action=impl.StripQuotesAction)
parser.add_argument("-echo-rule-name", action="store_true")

(options, args) = parser.parse_known_args()

# ranlib may have hid the archive next to what buck thinks the archive is
input = args[-1] + ".secret"

if not os.path.exists(input):
    input = args[-1]

with open(options.output, "w") as output:
    output.write("linker:\n")
    target_from_env = os.environ.get("BUCK_BUILD_TARGET")
    if target_from_env and options.echo_rule_name:
        output.write("BUCK_BUILD_TARGET: " + target_from_env + "\n")
    if options.test_arg:
        with open(options.test_arg) as inputfile:
            output.write("test arg: " + inputfile.read())
    with open(input) as inputfile:
        output.write(inputfile.read())

sys.exit(0)
