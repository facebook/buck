#!/usr/bin/env python3

import build_file
import repository

import argparse
import os


class StoreKeyValuePair(argparse.Action):
    """Parser action that populates a dictionary with '=' separated key-value pairs."""

    def __call__(self, parser, namespace, values, option_string=None):
        key, value = values.split('=')
        dest_dict = {}
        if hasattr(namespace, self.dest) and getattr(namespace, self.dest):
            dest_dict = getattr(namespace, self.dest)
        dest_dict[key] = value
        setattr(namespace, self.dest, dest_dict)


def dump_exported_symbols(args):
    bf = build_file.from_path(args.build_file)
    repo = repository.Repository(args.cell_roots)
    symbols = bf.get_exported_symbols_transitive_closure(repo)
    if args.json:
        import json
        print(json.dumps(symbols))
    else:
        print(os.linesep.join(symbols))


def dump_export_map(args):
    """Prints export map that includes all included definitions and symbols they export."""
    bf = build_file.from_path(args.build_file)
    repo = repository.Repository(args.cell_roots)
    export_map = bf.get_export_map(repo)
    if args.json:
        import json
        print(json.dumps(export_map))
    else:
        for import_string, exported_symbols in export_map.items():
            print(import_string + ':')
            for exported_symbol in exported_symbols:
                print(' ' * 2 + exported_symbol)


def main():
    parser = argparse.ArgumentParser(description='Dumps requested build file information.')
    subparsers = parser.add_subparsers()

    exported_symbols_parser = subparsers.add_parser('exported_symbols')
    exported_symbols_parser.set_defaults(func=dump_exported_symbols)

    export_map_parser = subparsers.add_parser('export_map')
    export_map_parser.set_defaults(func=dump_export_map)

    parser.add_argument('build_file', metavar='FILE')
    parser.add_argument('--json', action='store_true')
    parser.add_argument('--cell_root', action=StoreKeyValuePair, metavar='CELL=PATH',
                        dest='cell_roots')
    args = parser.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
