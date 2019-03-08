import argparse
import sys


def argparser():
    return argparse.ArgumentParser(fromfile_prefix_chars="@")


# We configure out toolchain to be gnu-like on all platforms. In that case, Buck will write args in
# a way that is parseable by gcc, but on Windows python might parse it incorrectly. The only way
# this manifests that matters is that our files will sometimes have double quotes that we need to
# ignore. This argparse action will handle this for us.
class StripQuotesAction(argparse.Action):
    def __call__(self, parser, namespace, values, option_string=None):
        value = str(values)
        if value[0] == '"' and value[-1] == '"':
            value = value[1:-1]
        setattr(namespace, self.dest, value)


def log(v):
    sys.stderr.write(str(v))
    sys.stderr.write("\n")


def run(cmd):
    sys.stderr.write(cmd)
    sys.stderr.write(" ".join(parse_args()))


# To make debugging easier, just always log the args when this module is loaded
log(sys.argv)