#!/usr/bin/env python
from __future__ import print_function
import os
import signal
import sys
from buck_repo import BuckRepo, BuckRepoException
from buck_project import BuckProject, NoBuckConfigFoundException

THIS_DIR = os.path.dirname(os.path.abspath(__file__))


def main():
    with BuckProject.from_current_dir() as project:
        buck_repo = BuckRepo(THIS_DIR, project)
        if 'clean' in sys.argv:
            buck_repo.kill_buckd()
        exit_code = buck_repo.launch_buck()
        sys.exit(exit_code)

if __name__ == "__main__":
    try:
        main()
    except (BuckRepoException, NoBuckConfigFoundException) as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(1)
