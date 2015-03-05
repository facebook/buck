#!/usr/bin/env python
from __future__ import print_function
import os
import signal
import sys
import zipfile
from buck_tool import BuckToolException, RestartBuck
from buck_project import BuckProject, NoBuckConfigFoundException
from tracing import Tracing
import uuid

THIS_DIR = os.path.dirname(os.path.realpath(__file__))


def main(argv):
    try:
        java_home = os.getenv("JAVA_HOME", "")
        path = os.getenv("PATH", "")
        if java_home:
            pathsep = os.pathsep
            os.environ["PATH"] = os.path.join(java_home, 'bin') + pathsep + path

        tracing_dir = None
        build_id = str(uuid.uuid4())
        with Tracing("main"):
            with BuckProject.from_current_dir() as project:
                tracing_dir = os.path.join(project.get_buck_out_log_dir(), 'traces')

                # Try to detect if we're running a PEX by checking if we were invoked
                # via a zip file.
                if zipfile.is_zipfile(argv[0]):
                    from buck_package import BuckPackage
                    buck_repo = BuckPackage(project)
                else:
                    from buck_repo import BuckRepo
                    buck_repo = BuckRepo(THIS_DIR, project)

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
        sys.exit(main(sys.argv))
    except RestartBuck:
        os.execvp(os.path.join(os.path.dirname(THIS_DIR), 'bin', 'buck'), sys.argv)
    except (BuckToolException, NoBuckConfigFoundException) as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(1)
