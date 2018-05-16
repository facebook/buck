#!/usr/bin/env python

from __future__ import print_function
import sys


class ExitCode(object):
    """Python equivalent of com.facebook.buck.util.ExitCode"""
    SUCCESS = 0
    COMMANDLINE_ERROR = 3
    FATAL_GENERIC = 10
    FATAL_BOOTSTRAP = 11
    FATAL_IO = 13
    FATAL_DISK_FULL = 14
    SIGNAL_INTERRUPT = 130
    SIGNAL_PIPE = 141


if sys.version_info < (2, 7):
    import platform
    print(("Buck requires at least version 2.7 of Python, but you are using {}."
           "\nPlease follow https://buckbuild.com/setup/getting_started.html " +
           "to properly setup your development environment.").format(platform.version()))
    sys.exit(ExitCode.FATAL_BOOTSTRAP)

import logging
import os
import signal
import subprocess
import re
import uuid
import zipfile
import errno

from buck_logging import setup_logging
from buck_tool import ExecuteTarget, install_signal_handlers, \
    get_java_path, BuckStatusReporter, BuckDaemonErrorException
from buck_project import BuckProject, NoBuckConfigFoundException
from tracing import Tracing
from subprocutils import propagate_failure
from subprocess import check_output

THIS_DIR = os.path.dirname(os.path.realpath(__file__))
REQUIRED_JAVA_VERSION = "8"


# Kill all buck processes
def killall_buck(reporter):
    # Linux or macOS
    if os.name != 'posix':
        message = 'killall is not implemented on: ' + os.name
        logging.error(message)
        reporter.status_message = message
        return ExitCode.COMMANDLINE_ERROR

    for line in os.popen('jps -l'):
        split = line.split()
        if len(split) == 1:
            # Java processes which are launched not as `java Main`
            # (e. g. `idea`) are shown with only PID without
            # main class name.
            continue
        if len(split) != 2:
            raise Exception('cannot parse a line in jps -l outout: ' +
                            repr(line))
        pid = int(split[0])
        name = split[1]
        if name != 'com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper':
            continue

        os.kill(pid, signal.SIGTERM)
        # TODO(buck_team) clean .buckd directories
    return ExitCode.SUCCESS


def _get_java_version(java_path):
    """
    Returns a Java version string (e.g. "7", "8").

    Information is provided by java tool and parsing is based on
    http://www.oracle.com/technetwork/java/javase/versioning-naming-139433.html
    """
    java_version = check_output([java_path, "-version"], stderr=subprocess.STDOUT)
    # extract java version from a string like 'java version "1.8.0_144"'
    match = re.search("java version \"(?P<version>.+)\"", java_version)
    if not match:
        return None
    pieces = match.group("version").split(".")
    if pieces[0] != "1":
        # versions starting at 9 look like "9.0.4"
        return pieces[0]
    # versions <9 look like "1.8.0_144"
    return pieces[1]


def _warn_about_wrong_java_version(required_version, actual_version):
    """
    Prints a warning about actual Java version being incompatible with the one
    required by Buck.
    """
    logging.warning(
        "You're using Java %s, but Buck requires Java %s.\nPlease follow " +
        "https://buckbuild.com/setup/getting_started.html " +
        "to properly setup your local environment and avoid build issues.",
        actual_version, required_version)


def _try_to_verify_java_version():
    """
    Best effort check to make sure users have required Java version installed.
    """
    java_path = get_java_path()
    try:
        java_version = _get_java_version(java_path)
        if java_version and java_version != REQUIRED_JAVA_VERSION:
            _warn_about_wrong_java_version(REQUIRED_JAVA_VERSION, java_version)
    except:
        # checking Java version is brittle and as such is best effort
        logging.warning("Cannot verify that installed Java version at '{}' \
is correct.".format(java_path))


def main(argv, reporter):
    _try_to_verify_java_version()

    def get_repo(p):
        # Try to detect if we're running a PEX by checking if we were invoked
        # via a zip file.
        if zipfile.is_zipfile(argv[0]):
            from buck_package import BuckPackage
            return BuckPackage(p, reporter)
        else:
            from buck_repo import BuckRepo
            return BuckRepo(THIS_DIR, p, reporter)

    # If 'killall' is the second argument, shut down all the buckd processes
    if sys.argv[1:] == ['killall']:
        return killall_buck(reporter)

    install_signal_handlers()
    try:
        tracing_dir = None
        build_id = os.environ.get('BUCK_BUILD_ID', str(uuid.uuid4()))
        reporter.build_id = build_id
        with Tracing("main"):
            with BuckProject.from_current_dir() as project:
                tracing_dir = os.path.join(project.get_buck_out_log_dir(),
                                           'traces')
                with get_repo(project) as buck_repo:
                    # If 'kill' is the second argument, shut down the buckd
                    # process
                    if sys.argv[1:] == ['kill']:
                        buck_repo.kill_buckd()
                        return ExitCode.SUCCESS
                    return buck_repo.launch_buck(build_id)
    finally:
        if tracing_dir:
            Tracing.write_to_dir(tracing_dir, build_id)


if __name__ == "__main__":
    exit_code = ExitCode.SUCCESS
    reporter = BuckStatusReporter(sys.argv)
    fn_exec = None
    exception = None
    try:
        setup_logging()
        exit_code = main(sys.argv, reporter)
    except ExecuteTarget as e:
        # this is raised once 'buck run' has the binary
        # it can get here only if exit_code of corresponding buck build is 0
        fn_exec = e.execve
    except NoBuckConfigFoundException:
        exc_type, exception, exc_traceback = sys.exc_info()
        # buck is started outside project root
        exit_code = ExitCode.COMMANDLINE_ERROR
    except BuckDaemonErrorException:
        reporter.status_message = 'Buck daemon disconnected unexpectedly'
        _, exception, _ = sys.exc_info()
        print(str(exception))
        exception = None
        exit_code = ExitCode.FATAL_GENERIC
    except IOError as e:
        exc_type, exception, exc_traceback = sys.exc_info()
        if e.errno == errno.ENOSPC:
            exit_code = ExitCode.FATAL_DISK_FULL
        elif e.errno == errno.EPIPE:
            exit_code = ExitCode.SIGNAL_PIPE
        else:
            exit_code = ExitCode.FATAL_IO
    except KeyboardInterrupt:
        reporter.status_message = 'Python wrapper keyboard interrupt'
        exit_code = ExitCode.SIGNAL_INTERRUPT
    except Exception:
        exc_type, exception, exc_traceback = sys.exc_info()
        exit_code = ExitCode.FATAL_BOOTSTRAP

    if exception is not None:
        logging.error(exception, exc_info=(exc_type, exception, exc_traceback))
        if reporter.status_message is None:
            reporter.status_message = str(exception)

    # report result of Buck call
    try:
        reporter.report(exit_code)
    except Exception as e:
        logging.debug(str(e))

    # execute 'buck run' target
    if fn_exec is not None:
        fn_exec()

    propagate_failure(exit_code)
