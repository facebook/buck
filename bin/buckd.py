#!/usr/bin/env python
from __future__ import print_function
import os
import sys
from buck_repo import BuckRepo, BuckRepoException
from buck_project import BuckProject, NoBuckConfigFoundException

THIS_DIR = os.path.dirname(os.path.abspath(__file__))


def main():
    with BuckProject.from_current_dir() as project:
        buck_repo = BuckRepo(THIS_DIR, project)
        if '--help' in sys.argv:
            print("Specify --kill to kill buckd.", file=sys.stderr)
            sys.exit(0)
        buck_repo.kill_buckd()
        if '--kill' in sys.argv:
            sys.exit(0)
        exit_code = buck_repo.launch_buckd()
        sys.exit(exit_code)

if __name__ == "__main__":
    try:
        main()
    except (BuckRepoException, NoBuckConfigFoundException) as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(1)
