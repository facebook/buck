import build_file
import repository

import argparse
import os


def main():
    parser = argparse.ArgumentParser(
        description='Dumps all symbols exported from provided file and files it imports.',
    )
    parser.add_argument('build_file', metavar='FILE')
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    bf = build_file.from_path(args.build_file)
    repo = repository.Repository({})
    symbols = bf.get_exported_symbols_transitive_closure(repo)
    if args.json:
        import json
        print(json.dumps(symbols))
    else:
        print(os.linesep.join(symbols))


if __name__ == '__main__':
    main()
