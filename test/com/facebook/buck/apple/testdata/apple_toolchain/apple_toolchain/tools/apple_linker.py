#!/usr/bin/env python3

import os
import sys

from tools import impl


parser = impl.argparser()

parser.add_argument("-o", dest="output", action=impl.StripQuotesAction)
parser.add_argument("-L", dest="lpath", action="append", default=[])
parser.add_argument("-F", dest="fpath", action="append", default=[])
parser.add_argument("-framework", dest="frameworks", action="append", default=[])
parser.add_argument("-add_ast_path", dest="ast_paths", action="append", default=[])
parser.add_argument("-test-flag", action=impl.StripQuotesAction)

(options, args) = parser.parse_known_args()

libs = [arg[2:] for arg in args if arg.startswith("-l")]

inputs = [arg for arg in args if not arg.startswith("-")]

impl.log(args)

for lpath in options.lpath:
    assert os.path.exists(lpath), lpath
for fpath in options.fpath:
    assert os.path.exists(fpath), fpath
for ast_path in options.ast_paths:
    assert os.path.exists(ast_path), ast_path

assert os.path.exists(options.test_flag), options.test_flag

with open(options.output, "w") as output:
    for input in inputs:
        output.write("linker: input:\n")
        # ranlib may have hid the archive next to what buck thinks the archive is
        if os.path.exists(input + ".secret"):
            input = input + ".secret"
        with open(input) as inputfile:
            output.write(input + "\n")
            output.write(inputfile.read())
    print("linker: fpath:", ",".join(options.fpath), file=output)
    print("linker: frameworks:", ",".join(options.frameworks), file=output)
    print("linker: lpath:", ",".join(options.lpath), file=output)
    print("linker: libs:", ",".join(libs), file=output)
    if options.ast_paths:
        print("linker: ast_paths:", ",".join(options.ast_paths), file=output)

sys.exit(0)
