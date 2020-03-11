#!/usr/bin/env python3

import os
import sys

from tools import impl


def _extract_linker_flags(arg):
    if arg.startswith("-Wl,"):
        # Process flags like -Wl,-undefined,dynamic_lookup
        params = arg[len("-Wl,") :]
        return params.split(",")
    elif arg == "-Xlinker":
        # -Xlinker arguments are just markers to the driver,
        # they're not passed to the linker
        return []
    else:
        return [arg]


# Buck calls the Clang driver to do linking, that's why in BUCK files
# we have to use -Xlinker or -Wl, arguments to be passed directly
# to the linker. The filtering done here acts as the linker driver.
def _extract_linker_args_from_driver_args():
    if not (len(sys.argv) == 2 and sys.argv[1].startswith("@")):
        return None
    argfile_path = sys.argv[1][1:]
    if not os.path.exists(argfile_path):
        return None

    with open(argfile_path, "r") as argfile:
        all_args = argfile.read().splitlines()
        processed_arg_lines = [_extract_linker_flags(argline) for argline in all_args]
        processed_args = []
        for arg_lines in processed_arg_lines:
            processed_args.extend(arg_lines)
        return processed_args

    return None


linker_args = _extract_linker_args_from_driver_args()

parser = impl.argparser()

parser.add_argument("-o", dest="output", action=impl.StripQuotesAction)
parser.add_argument("-L", dest="lpath", action="append", default=[])
parser.add_argument("-F", dest="fpath", action="append", default=[])
parser.add_argument("-framework", dest="frameworks", action="append", default=[])
parser.add_argument("-add_ast_path", dest="ast_paths", action="append", default=[])
parser.add_argument("-test-flag", action=impl.StripQuotesAction)

(options, args) = parser.parse_known_args(args=linker_args)

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
