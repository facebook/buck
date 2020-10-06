# Copyright (c) Facebook, Inc. and its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import datetime
import logging
import multiprocessing
import os
import subprocess
import sys
import tempfile
import time


# Reconsider before importing Buck modules


# Timeout in sec for filesystem-related system calls
_FS_TIMEOUT_S = 10


def timed_exec(func, args=(), timeout_s=None):
    """
    Could raise TimeoutError in addition to ones func could raise
    """

    def _te(q, f, args):
        try:
            ret = f(*args)
            q.put(ret)
        except Exception as e:
            q.put(e)

    q = multiprocessing.Queue()
    p = multiprocessing.Process(target=_te, args=(q, func, args))
    p.start()
    p.join(timeout_s)
    p.terminate()
    if p.exitcode == 0:
        ret = q.get_nowait()
        if isinstance(ret, Exception):
            raise ret
        return ret
    raise TimeoutError


def fs_safe_getcwd():
    cwd = None
    try:
        cwd = timed_exec(os.getcwd, timeout_s=_FS_TIMEOUT_S)
    except FileNotFoundError:
        logging.error("os.getcwd failed: CWD disappeared")
    except TimeoutError:
        logging.error("os.getcwd failed: timed out")
    return cwd


def fs_safe_path_exists(path):
    ret = None
    try:
        ret = timed_exec(os.path.exists, (path,), _FS_TIMEOUT_S)
    except TimeoutError:
        logging.error("os.path.exists failed: timed out")
    return ret


def fs_safe_write(path, data):
    def _fsw(path, data):
        with open(path, "w") as f:
            return f.write(data)

    ret = -1
    try:
        ret = timed_exec(_fsw, (path, data), _FS_TIMEOUT_S)
    except TimeoutError:
        logging.error("write failed: timed out")
    except OSError:
        logging.error("write failed: I/O error")
    return ret


def fs_safe_makedirs(name, mode=0o777, exist_ok=False):
    ret = False
    try:
        timed_exec(os.makedirs, (name, mode, exist_ok), _FS_TIMEOUT_S)
        ret = True
    except FileNotFoundError:
        logging.error("os.makedirs failed: CWD disappeared")
    except TimeoutError:
        logging.error("os.makedirs failed: timed out")
    return ret


def get_project_root_abspath(key_file=".buckconfig"):
    """
    Return abs path to project root as string, or None if not found.
    """
    current_dir = fs_safe_getcwd()
    if current_dir is None:
        return None
    at_root_dir = False
    while not at_root_dir:
        exists = fs_safe_path_exists(os.path.join(current_dir, key_file))
        if exists is None:
            # Filesystem is unhealthy. Quit searching
            return None
        if exists:
            return current_dir
        parent_dir = os.path.dirname(current_dir)
        at_root_dir = current_dir == parent_dir
        current_dir = parent_dir
    return None


def time_prefix():
    """
    Return string 'yyyy-mm-dd.hh-mm-ss.us'
    """
    return datetime.datetime.now().strftime("%Y-%m-%d.%H-%M-%S.%f")


def _setup_logging():
    """
    Equivalent of programs.buck_logging.setup_logging.
    Should be called only when buck_sosreport.py is directly invoked.
    """
    level_name = os.environ.get("BUCK_WRAPPER_LOG_LEVEL", "INFO")
    level_name_to_level = {
        "CRITICAL": logging.CRITICAL,
        "ERROR": logging.ERROR,
        "WARNING": logging.WARNING,
        "INFO": logging.INFO,
        "DEBUG": logging.DEBUG,
        "NOTSET": logging.NOTSET,
    }
    level = level_name_to_level.get(level_name.upper(), logging.INFO)
    if level == logging.INFO:
        format_string = "%(message)s"
    else:
        format_string = (
            "%(asctime)s [%(levelname)s][%(filename)s:%(lineno)d] %(message)s"
        )
    logging.basicConfig(level=level, format=format_string)


class SOSReportExitCode:
    """
    Equivalent of programs.buck_tool.ExitCode
    """

    SUCCESS = 0
    COMMANDLINE_ERROR = 3
    FATAL_GENERIC = 10
    FATAL_BOOTSTRAP = 11
    FATAL_IO = 13
    FATAL_DISK_FULL = 14
    FIX_FAILED = 16
    SIGNAL_INTERRUPT = 130
    SIGNAL_PIPE = 141


class SOSReport:
    def __init__(self, absroot=None):
        self.absroot = absroot or get_project_root_abspath()
        if self.absroot is not None:
            self.buckd_dir = os.path.join(self.absroot, ".buckd")
            self.buckd_logdir = os.path.join(self.absroot, "buck-out", "log")
            self.sos_outdir = os.path.join(
                self.buckd_logdir, "%s_sosreport" % time_prefix()
            )

    def _get_buckd_pids(self):
        """
        Return PIDs of current project's buckd, or [] on failure.
        """
        pids = []
        key_clsname = "com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper"
        key_buckdcfg = "-Dbuck.buckd_dir=%s" % self.buckd_dir
        try:
            res = subprocess.run(["ps", "auxww"], capture_output=True)
            if res.returncode == 0:
                for line in res.stdout.splitlines():
                    # make a list of ["user", "pid", "rest..."]
                    lline = line.strip().split(maxsplit=2)
                    if len(lline) < 3:
                        logging.warning(
                            "_get_buckd_pids found an irregular line of ps. Skipping: %s",
                            line.decode(errors="replace"),
                        )
                        continue
                    cmdargs = lline[-1].strip().split()
                    if (
                        key_clsname.encode() in cmdargs
                        and key_buckdcfg.encode() in cmdargs
                    ):
                        try:
                            pid = int(lline[1])
                            pids.append(pid)
                        except ValueError as e:
                            logging.warning(
                                "_get_buckd_pids found an irregular line of ps (%s): %s",
                                line.decode(errors="replace"),
                                repr(e),
                            )
            else:
                logging.warning(
                    "ps command failed: %s", repr(res.stderr.decode(errors="replace"))
                )
        except FileNotFoundError as e:
            logging.warning("_get_buckd_pids failed to get Buckd PIDs: %s", repr(e))
        return pids

    def _get_jstack_once(self, pids, ntry=0):
        for pid in pids:
            filename = os.path.join(
                self.sos_outdir, "%s_jstack_%d_%d" % (time_prefix(), pid, ntry)
            )
            try:
                res = subprocess.run(["jstack", "-l", str(pid)], capture_output=True)
                if res.returncode == 0:
                    written = fs_safe_write(
                        filename, res.stdout.decode(errors="replace")
                    )
                    if written < 0:
                        logging.error("Failed to log %s", filename)
                else:
                    logging.warning(
                        "jstack command failed: %s",
                        repr(res.stderr.decode(errors="replace")),
                    )
            except FileNotFoundError as e:
                logging.warning("_get_jstack_once failed to get jstack: %s", repr(e))

    def _get_jstack(self, count=10, interval_s=5):
        for n in range(count):
            logging.info(
                "Getting jstack (%d/%d) with interval %d sec.", n + 1, count, interval_s
            )
            self._get_jstack_once(self._get_buckd_pids(), n)
            time.sleep(interval_s)

    def gen_report(self):
        """
        Generate SOS report under log directory.
        Return SOSReportexitcode and message as tupple.
        """
        if self.absroot is None:
            msg = "Failed to identify Buck project"
            logging.error(msg)
            return SOSReportExitCode.COMMANDLINE_ERROR, msg

        if os.name == "nt":
            # TODO: windows platform version
            msg = "sosreport is not implemented on: %s" % os.name
            logging.error(msg)
            return SOSReportExitCode.COMMANDLINE_ERROR, msg

        makedirs = fs_safe_makedirs(self.sos_outdir, exist_ok=True)
        if not makedirs:
            tmpdir = os.path.join(tempfile.gettempdir(), "%s_sosreport" % time_prefix())
            makedirs = fs_safe_makedirs(tmpdir, exist_ok=True)
            if not makedirs:
                msg = "Failed to find a location to store reports"
                logging.error(msg)
                return SOSReportExitCode.FATAL_BOOTSTRAP, msg
            logging.warning(
                "Failed to locate %s. Falling back to tempdir %s to store reports",
                self.sos_outdir,
                tmpdir,
            )
            self.sos_outdir = tmpdir
        logging.info("Generating report under %s", self.sos_outdir)

        # jstack
        self._get_jstack()

        # TODO: other metrics
        return SOSReportExitCode.SUCCESS, "success"


def sosreport_main(proj_root=None):
    logging.info("Running sosreport..")
    sr = SOSReport(proj_root)
    if sr.absroot is None:
        logging.error("Failed to identify Buck project")
        retcode = SOSReportExitCode.FATAL_BOOTSTRAP
        msg = "Failed to identify Buck project"
    else:
        logging.info("Making report on the following project: %s", sr.absroot)
        retcode, msg = sr.gen_report()
    logging.info('sosreport finished with msg: "%s" and retcode: %d', msg, retcode)
    return retcode


if __name__ == "__main__":
    _setup_logging()
    if len(sys.argv) > 2:
        # assume sys.argv[2] is "/full/path/to/project_root"
        sys.exit(sosreport_main(sys.argv[2]))
    else:
        sys.exit(sosreport_main())
