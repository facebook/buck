#!/usr/bin/env python
#
# Examines the output from running Javadoc via Ant and checks to see if any
# warnings were emitted. If so, fail the build unless the warning is in the
# whitelist. When run in a CI build, Ant may not be able to reach external
# URLs, so warnings about errors fetching expected URLs should be ignored.


import sets
import sys


WARNING_WHITELIST = sets.ImmutableSet(map(
    lambda url: '  [javadoc] javadoc: warning - Error fetching URL: ' + url,
    [
        'http://docs.oracle.com/javase/7/docs/api/package-list',
        'https://junit-team.github.io/junit/javadoc/latest/package-list',
    ]) + ['  [javadoc] 2 warnings'])


def main(log_file):
    """Exit with a non-zero return code if line is not in the warning whitelist."""
    errors = []
    with open(log_file) as f:
        for line in f.xreadlines():
            line = line.rstrip()
            # If there is a warning from `javadoc`, check whether it is in the whitelist.
            if 'warning' in line.lower() and line not in WARNING_WHITELIST:
                errors.append(line)
    if len(errors):
        print 'Unexpected Javadoc errors (%d):' % len(errors)
        for error in errors:
            print error
        sys.exit(1)


if __name__ == '__main__':
    main(sys.argv[1])
