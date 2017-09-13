#!/usr/bin/env python
from __future__ import print_function
import logging
import os
import sys
import uuid
import zipfile

from buck_logging import setup_logging
from buck_tool import BuckToolException, ExecuteTarget, install_signal_handlers
from buck_project import BuckProject, NoBuckConfigFoundException
from pynailgun.ng import NailgunException
from tracing import Tracing
from subprocutils import propagate_failure

THIS_DIR = os.path.dirname(os.path.realpath(__file__))


def main(argv):
    def get_repo(p):
        # Try to detect if we're running a PEX by checking if we were invoked
        # via a zip file.
        if zipfile.is_zipfile(argv[0]):
            from buck_package import BuckPackage
            return BuckPackage(p)
        else:
            from buck_repo import BuckRepo
            return BuckRepo(THIS_DIR, p)

    install_signal_handlers()
    try:
        tracing_dir = None
        build_id = str(uuid.uuid4())
        with Tracing("main"):
            with BuckProject.from_current_dir() as project:
                tracing_dir = os.path.join(project.get_buck_out_log_dir(), 'traces')

                with get_repo(project) as buck_repo:
                    # If 'kill' is the second argument, shut down the buckd process.
                    if sys.argv[1:] == ['kill']:
                        buck_repo.kill_buckd()
                        return 0
                    return buck_repo.launch_buck(build_id)
    finally:
        if tracing_dir:
            Tracing.write_to_dir(tracing_dir, build_id)

if __name__ == "__main__":
    try:
        setup_logging()
        propagate_failure(main(sys.argv))
    except ExecuteTarget as e:
        e.execve()
    except (BuckToolException, NailgunException, NoBuckConfigFoundException) as e:
        logging.error(str(e))
        sys.exit(1)
    except KeyboardInterrupt:
        # Most shells set exit code to 128 + <Signal Number>
        # So, when catching SIGINT (2), we return 130
        sys.exit(130)
