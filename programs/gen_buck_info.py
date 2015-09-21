import errno
import os
import json
import sys
import time

import buck_version


def main(argv):

    # Locate the root of the buck repo.  We'll need to be there to
    # generate the buck version UID.
    path = os.getcwd()
    while not os.path.exists(os.path.join(path, '.buckconfig')):
        path = os.path.dirname(path)

    if os.path.exists(os.path.join(path, '.git')):
        # Attempt to create a "clean" version, but fall back to a "dirty"
        # one if need be.
        version = buck_version.get_clean_buck_version(path)
        timestamp = -1
        if version is None:
            version = buck_version.get_dirty_buck_version(path)
        else:
            timestamp = buck_version.get_git_revision_timestamp(path)
    else:
        # We're building outside a git repo. Check for the special
        # .buckrelease file created by the release process.
        try:
            with open(os.path.join(path, '.buckrelease')) as f:
                timestamp = int(os.fstat(f.fileno()).st_mtime)
                version = f.read().strip()
        except IOError, e:
            if e.errno == errno.ENOENT:
                # No .buckrelease file. Do the best that we can.
                version = '(unknown version)'
                timestamp = int(time.time())
            else:
                raise e

    json.dump(
        {'version': version, 'timestamp': timestamp},
        sys.stdout,
        sort_keys=True,
        indent=2)


sys.exit(main(sys.argv))
