import json
import optparse
import shutil
import sys


def main(argv):
    parser = optparse.OptionParser()
    parser.add_option("--entry-point")
    parser.add_option("--interpreter")
    options, args = parser.parse_args(argv[1:])

    with open(args[0], "w") as f:
        shutil.copyfileobj(sys.stdin, f)


sys.exit(main(sys.argv))
