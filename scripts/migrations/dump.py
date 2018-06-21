#!/usr/bin/env python3

import argparse
import json
import logging
import os
from typing import List

import build_file
import label
import repository


class StoreKeyValuePair(argparse.Action):
    """
    Parser action that populates a dictionary with '=' separated key-value
    pairs.
    """

    def __call__(self, parser, namespace, values, option_string=None):
        key, value = values.split("=")
        dest_dict = {}
        if hasattr(namespace, self.dest) and getattr(namespace, self.dest):
            dest_dict = getattr(namespace, self.dest)
        dest_dict[key] = value
        setattr(namespace, self.dest, dest_dict)


def dump_exported_symbols(args):
    """Print all symbols exported using include_defs in a build file."""
    logging.debug("Dumping exported symbols for " + args.build_file)
    bf = build_file.from_path(args.build_file)
    repo = repository.Repository(args.repository, args.cell_roots)
    symbols = bf.get_exported_symbols_transitive_closure(repo)
    if args.json:
        print(json.dumps(symbols))
    else:
        print(os.linesep.join(symbols))


def dump_export_map(args):
    """
    Prints export map that includes all included definitions and symbols they
    export.
    """
    logging.debug("Dumping export map for " + args.build_file)
    bf = build_file.from_path(args.build_file)
    repo = repository.Repository(args.repository, args.cell_roots)
    export_map = bf.get_export_map(repo)

    def to_load_import_string(import_label: label):
        pkg = import_label.package
        # include_defs package includes a file name, so we have to split it
        # into file name
        file_name = pkg.split("/")[-1]
        # and it's prefix - which is the new package
        pkg = "/".join(pkg.split("/")[:-1])
        load_fn_cell = args.cell_prefix + import_label.cell if import_label.cell else ""
        return load_fn_cell + "//" + pkg + ":" + file_name

    if args.use_load_function_import_string_format:
        new_export_map = {}
        for import_string, exported_symbols in export_map.items():
            new_export_map[
                to_load_import_string(label.from_string(import_string))
            ] = exported_symbols
        export_map = new_export_map

    if args.print_as_load_functions:

        def to_load_function(import_label: label, symbols: List[str]):
            import_string = to_load_import_string(import_label)
            function_args = map(lambda s: '"%s"' % s, symbols)
            return 'load("%s", %s)' % (import_string, ",".join(function_args))

        load_functions = []
        for import_string, exported_symbols in export_map.items():
            load_functions.append(
                to_load_function(label.from_string(import_string), exported_symbols)
            )
        if args.json:
            print(json.dumps(load_functions))
        else:
            print(os.linesep.join(load_functions))
    elif args.json:
        print(json.dumps(export_map))
    else:
        for import_string, exported_symbols in export_map.items():
            print(import_string + ":")
            for exported_symbol in exported_symbols:
                print(" " * 2 + exported_symbol)


def main():
    parser = argparse.ArgumentParser(
        description="Dumps requested build file information."
    )
    subparsers = parser.add_subparsers()

    exported_symbols_parser = subparsers.add_parser("exported_symbols")
    exported_symbols_parser.set_defaults(func=dump_exported_symbols)

    export_map_parser = subparsers.add_parser("export_map")
    export_map_parser.add_argument(
        "--print_as_load_functions",
        action="store_true",
        help="Print export map as a series of load functions which import all "
        "symbols exported by respective imported files.",
    )
    export_map_parser.add_argument(
        "--cell_prefix",
        default="",
        help="The prefix to use for cells in import strings.",
    )
    export_map_parser.add_argument(
        "--use_load_function_import_string_format",
        action="store_true",
        help="Use load function import string syntax instead of include_defs.",
    )
    export_map_parser.set_defaults(func=dump_export_map)

    parser.add_argument("build_file", metavar="FILE")
    parser.add_argument("--json", action="store_true")
    parser.add_argument(
        "--cell_root", action=StoreKeyValuePair, metavar="CELL=PATH", dest="cell_roots"
    )
    parser.add_argument(
        "--repository", metavar="PATH", help="Path to the repository managed by Buck."
    )
    parser.add_argument(
        "-v", "--verbose", action="store_true", help="Enabled verbose diagnostic."
    )
    args = parser.parse_args()
    logging_level = logging.DEBUG if args.verbose else logging.INFO
    logging.basicConfig(
        level=logging_level,
        format=("%(asctime)s [%(levelname)s][%(filename)s:%(lineno)d] %(message)s"),
    )
    args.func(args)


if __name__ == "__main__":
    main()
