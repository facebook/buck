#!/usr/bin/env python
from __future__ import print_function
import logging
import os
import sys
import zipfile

from buck_logging import setup_logging
from buck_tool import BuckToolException, install_signal_handlers
from buck_project import BuckProject, NoBuckConfigFoundException
from subprocutils import propagate_failure

THIS_DIR = os.path.dirname(os.path.realpath(__file__))


def main(argv):
    install_signal_handlers()
    with BuckProject.from_current_dir() as project:
        # Try to detect if we're running a PEX by checking if we were invoked
        # via a zip file.
        if zipfile.is_zipfile(argv[0]):
            from buck_package import BuckPackage
            buck_repo = BuckPackage(project)
        else:
            from buck_repo import BuckRepo
            buck_repo = BuckRepo(THIS_DIR, project)
        if '--help' in argv:
            logging.info("Specify --kill to kill buckd.")
            return 0
        buck_repo.kill_buckd()
        if '--kill' in sys.argv:
            return 0
        return buck_repo.launch_buckd()

if __name__ == "__main__":
    try:
        setup_logging()
        propagate_failure(main(sys.argv))
    except (BuckToolException, NoBuckConfigFoundException) as e:
        logging.info(str(e))
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(1)
