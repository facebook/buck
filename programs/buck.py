#!/usr/bin/env python
# Copyright 2018-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.


from __future__ import print_function

import errno
import logging
import os
import re
import signal
import subprocess
import sys
import threading
import time
import uuid
import zipfile
from multiprocessing import Queue
from subprocess import check_output

from buck_logging import setup_logging
from buck_project import BuckProject, NoBuckConfigFoundException
from buck_tool import (
    BuckDaemonErrorException,
    BuckStatusReporter,
    ExecuteTarget,
    get_java_path,
    install_signal_handlers,
)
from subprocutils import propagate_failure
from tracing import Tracing


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

    print(
        (
            "Buck requires at least version 2.7 of Python, but you are using {}."
            "\nPlease follow https://buckbuild.com/setup/getting_started.html "
            + "to properly setup your development environment."
        ).format(platform.version())
    )
    sys.exit(ExitCode.FATAL_BOOTSTRAP)


THIS_DIR = os.path.dirname(os.path.realpath(__file__))


# Kill all buck processes
def killall_buck(reporter):
    # Linux or macOS
    if os.name != "posix" and os.name != "nt":
        message = "killall is not implemented on: " + os.name
        logging.error(message)
        reporter.status_message = message
        return ExitCode.COMMANDLINE_ERROR

    for line in os.popen("jps -l"):
        split = line.split()
        if len(split) == 1:
            # Java processes which are launched not as `java Main`
            # (e. g. `idea`) are shown with only PID without
            # main class name.
            continue
        if len(split) != 2:
            raise Exception("cannot parse a line in jps -l outout: " + repr(line))
        pid = int(split[0])
        name = split[1]
        if name != "com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper":
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
    java_version = check_output(
        [java_path, "-version"], stderr=subprocess.STDOUT
    ).decode("utf-8")
    # extract java version from a string like 'java version "1.8.0_144"'
    match = re.search('java version "(?P<version>.+)"', java_version)
    if not match:
        return None
    pieces = match.group("version").split(".")
    if pieces[0] != "1":
        # versions starting at 9 look like "9.0.4"
        return pieces[0]
    # versions <9 look like "1.8.0_144"
    return pieces[1]


def _try_to_verify_java_version(java_version_status_queue, required_java_version):
    """
    Best effort check to make sure users have required Java version installed.
    """
    java_path = get_java_path()
    warning = None
    try:
        java_version = _get_java_version(java_path)
        if java_version and java_version != required_java_version:
            warning = "You're using Java {}, but Buck requires Java {}.\nPlease follow \
https://buckbuild.com/setup/getting_started.html \
to properly setup your local environment and avoid build issues.".format(
                java_version, required_java_version
            )

    except:
        # checking Java version is brittle and as such is best effort
        warning = "Cannot verify that installed Java version at '{}' \
is correct.".format(
            java_path
        )
    java_version_status_queue.put(warning)


def _try_to_verify_java_version_off_thread(
    java_version_status_queue, required_java_version
):
    """ Attempts to validate the java version off main execution thread.
        The reason for this is to speed up the start-up time for the buck process.
        testing has shown that starting java process is rather expensive and on local tests,
        this optimization has reduced startup time of 'buck run' from 673 ms to 520 ms. """
    verify_java_version_thread = threading.Thread(
        target=_try_to_verify_java_version,
        args=(java_version_status_queue, required_java_version),
    )
    verify_java_version_thread.daemon = True
    verify_java_version_thread.start()


def _emit_java_version_warnings_if_any(java_version_status_queue):
    """ Emits java_version warnings that got posted in the java_version_status_queue
        queus from the java version verification thread.
        There are 2 cases where we need to take special care for.
         1. The main thread finishes before the main thread gets here before the version testing
         thread is done. In such case we wait for 50 ms. This should pretty much never happen,
         except in cases where buck deployment or the VM is really badly misconfigured.
         2. The java version thread never testing returns. This can happen if the process that is
         called java is hanging for some reason. This is also not a normal case, and in such case
         we will wait for 50 ms and if still no response, ignore the error."""
    if java_version_status_queue.empty():
        time.sleep(0.05)

    if not java_version_status_queue.empty():
        warning = java_version_status_queue.get()
        if warning is not None:
            logging.warning(warning)


def main(argv, reporter):
    java_version_status_queue = Queue(maxsize=1)
    required_java_version = "8"

    java11_test_mode_arg = "--java11-test-mode"
    java11_test_mode = java11_test_mode_arg in argv
    if java11_test_mode:
        argv.remove(java11_test_mode_arg)
        required_java_version = "11"

    _try_to_verify_java_version_off_thread(
        java_version_status_queue, required_java_version
    )

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
    if argv[1:] == ["killall"]:
        return killall_buck(reporter)

    install_signal_handlers()
    try:
        tracing_dir = None
        build_id = os.environ.get("BUCK_BUILD_ID", str(uuid.uuid4()))
        reporter.build_id = build_id
        with Tracing("main"):
            with BuckProject.from_current_dir() as project:
                tracing_dir = os.path.join(project.get_buck_out_log_dir(), "traces")
                with get_repo(project) as buck_repo:
                    # If 'kill' is the second argument, shut down the buckd
                    # process
                    if argv[1:] == ["kill"]:
                        buck_repo.kill_buckd()
                        return ExitCode.SUCCESS
                    return buck_repo.launch_buck(build_id, argv, java11_test_mode)
    finally:
        if tracing_dir:
            Tracing.write_to_dir(tracing_dir, build_id)
        _emit_java_version_warnings_if_any(java_version_status_queue)


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
        reporter.status_message = "Buck daemon disconnected unexpectedly"
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
        reporter.status_message = "Python wrapper keyboard interrupt"
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
        logging.debug(
            "Exception occurred while reporting build results. This error is "
            "benign and doesn't affect the actual build.",
            exc_info=True,
        )

    # execute 'buck run' target
    if fn_exec is not None:
        fn_exec()

    propagate_failure(exit_code)
