import os
import sys

import buck_version


def main(argv):

    # Locate the root of the buck repo.  We'll need to be there to
    # generate the buck version UID.
    path = os.getcwd()
    while not os.path.exists(os.path.join(path, '.buckconfig')):
        path = os.path.dirname(path)

    # Attempt to create a "clean" version, but fall back to a "dirty"
    # one if need be.
    version = buck_version.get_clean_buck_version(path)
    if version is None:
        version = buck_version.get_dirty_buck_version(path)
    sys.stdout.write(version)


sys.exit(main(sys.argv))
