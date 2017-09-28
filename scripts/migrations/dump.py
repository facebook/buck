#!/usr/bin/env python3

import build_file
import repository

import argparse
import os


def dump_exported_symbols(args):
    bf = build_file.from_path(args.build_file)
    repo = repository.Repository({})
    symbols = bf.get_exported_symbols_transitive_closure(repo)
    if args.json:
        import json
        print(json.dumps(symbols))
    else:
        print(os.linesep.join(symbols))


def main():
    parser = argparse.ArgumentParser(description='Dumps requested build file information.')
    subparsers = parser.add_subparsers()

    exported_symbols_parser = subparsers.add_parser('exported_symbols')
    exported_symbols_parser.set_defaults(func=dump_exported_symbols)
    parser.add_argument('build_file', metavar='FILE')
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
