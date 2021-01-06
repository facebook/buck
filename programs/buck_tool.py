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

from __future__ import print_function

import contextlib
import errno
import glob
import json
import logging
import os
import platform
import shlex
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import traceback
import uuid
from collections import namedtuple
from subprocess import CalledProcessError, check_output

from ng import NailgunConnection, NailgunException
from programs.file_locks import exclusive_lock
from programs.subprocutils import which
from programs.timing import monotonic_time_nanos
from programs.tracing import Tracing


BUCKD_CLIENT_TIMEOUT_MILLIS = 180000
BUCKD_STARTUP_TIMEOUT_MILLIS = 10000
GC_MAX_PAUSE_TARGET = 15000

JAVA_MAX_HEAP_SIZE_MB = 1000

# Files that are used to invoke programs after running buck. Used for buck run, and
# to automatically invoke buck fix scripts
PostRunFiles = namedtuple("PostRunFiles", ["command_args_file"])


class MovableTemporaryFile(object):
    def __init__(self, *args, **kwargs):
        self.file = None
        self._temp_file = None
        self._should_delete = True
        self._named_temporary_args = args
        self._named_temporary_kwargs = kwargs

    @property
    def name(self):
        if self.file:
            return self.file.name
        else:
            raise AttributeError(
                "`file` is not set. You cannot call name on a moved file"
            )

    def close(self):
        if self.file:
            self.file.close()

    def move(self):
        new_wrapper = MovableTemporaryFile(
            self._named_temporary_args, self._named_temporary_kwargs
        )
        new_wrapper.file = self.file
        new_wrapper._temp_file = self._temp_file
        self.file = None
        self._temp_file = None
        return new_wrapper

    def __enter__(self):
        if not self._temp_file:
            # Due to a bug in python (https://bugs.python.org/issue14243), we need to
            # be able to close() the temporary file without deleting it. So, set
            # delete=False, and cleanup manually on __exit__() rather than using
            # NamedTemproaryFile's built in delete logic
            self._temp_file = tempfile.NamedTemporaryFile(
                *self._named_temporary_args,
                delete=False,
                **self._named_temporary_kwargs
            )
            self.file = self._temp_file.__enter__()
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        if self._temp_file:
            self._temp_file.__exit__(exc_type, exc_value, traceback)
        if self.file:
            try:
                os.remove(self.file.name)
            except OSError as e:
                # It's possible this fails because of a race with another buck
                # instance has removed the entire resource_path, so ignore
                # 'file not found' errors.
                if e.errno != errno.ENOENT:
                    raise


class Resource(object):
    """Describes a resource used by this driver.

    :ivar name: logical name of the resources
    :ivar executable: whether the resource should/needs execute permissions
    :ivar basename: required basename of the resource
    """

    def __init__(self, name, executable=False, basename=None):
        self.name = name
        self.executable = executable
        self.basename = name if basename is None else basename


# Resource that get propagated to buck via system properties.
EXPORTED_RESOURCES = [
    Resource("legacy_fix_script", executable=True),
    Resource("testrunner_classes"),
    Resource("logging_config_file"),
    Resource("path_to_python_dsl"),
    Resource("path_to_pathlib_py", basename="pathlib.py"),
    Resource("path_to_pywatchman"),
    Resource("path_to_typing"),
    Resource("path_to_six_py", basename="six.py"),
    Resource("path_to_sh_binary_template"),
    Resource("path_to_isolated_trampoline"),
    Resource("jacoco_agent_jar"),
    Resource("report_generator_jar"),
    Resource("dx"),
    Resource("android_agent_path"),
    Resource("buck_build_type_info"),
    Resource("native_exopackage_fake_path"),
]


class CommandLineArgs:
    def __init__(self, cmdline):
        self.args = cmdline[1:]

        self.buck_options = []
        self.command = None
        self.command_options = []

        for arg in self.args:
            if arg == "--":
                break
            elif self.command is not None:
                self.command_options.append(arg)
            elif (arg[:1]) == "-":
                self.buck_options.append(arg)
            else:
                self.command = arg

    # Whether this is a help command that doesn't run a build
    # n.b. 'buck --help clean' is *not* currently a help command
    # n.b. 'buck --version' *is* a help command
    def is_help(self):
        # --help or -h can be anywhere except after ""--"
        return (
            self.command is None
            or any(v in self.command_options for v in ["--help", "-h"])
            or any(v in self.buck_options for v in ["--help", "-h"])
        )

    def is_version(self):
        return self.command is None and any(
            v in self.buck_options for v in ["--version", "-V"]
        )


class ExitCode(object):
    """Python equivalent of com.facebook.buck.util.ExitCode"""

    SUCCESS = 0
    COMMANDLINE_ERROR = 3
    FATAL_GENERIC = 10
    FATAL_BOOTSTRAP = 11
    FATAL_IO = 13
    FATAL_DISK_FULL = 14
    FIX_FAILED = 16
    SIGNAL_INTERRUPT = 130
    SIGNAL_PIPE = 141


class BuckToolException(Exception):
    pass


class BuckDaemonErrorException(BuckToolException):
    pass


class ExitCodeCallable(object):
    """ Simple callable class that just returns a given exit code """

    def __init__(self, exit_code):
        self.exit_code = exit_code

    def __call__(self):
        return self.exit_code


class ExecuteTarget(ExitCodeCallable):
    """ Callable that executes a given target after the build finishes """

    def __init__(self, exit_code, path, argv, envp, cwd):
        super(ExecuteTarget, self).__init__(exit_code)
        self._path = path
        self._argv = argv
        self._envp = envp
        self._cwd = cwd

    def __call__(self):
        if self.exit_code != ExitCode.SUCCESS:
            return self.exit_code

        # Restore default handling of SIGPIPE.  See https://bugs.python.org/issue1652.
        if os.name != "nt":
            signal.signal(signal.SIGPIPE, signal.SIG_DFL)
            os.execvpe(self._path, self._argv, self._envp)
        else:
            args = [self._path]
            args.extend(self._argv[1:])
            child = subprocess.Popen(args, env=self._envp, cwd=self._cwd)
            child.wait()
            sys.exit(child.returncode)


class ExecuteFixScript(ExitCodeCallable):
    """
    Callable that tries to execute a "fix" script, returning a different error
    if the fix script fails
    """

    def __init__(self, exit_code, path, argv, envp, cwd):
        super(ExecuteFixScript, self).__init__(exit_code)
        self._path = path
        self._argv = argv
        self._envp = envp
        self._cwd = cwd

    @contextlib.contextmanager
    def _disable_signal_handlers(self):
        if os.name == "nt":
            yield
        else:
            original_signal = signal.getsignal(signal.SIGINT)
            try:
                signal.signal(signal.SIGINT, signal.SIG_IGN)
                yield
            finally:
                signal.signal(signal.SIGINT, original_signal)

    def __call__(self):
        exit_code = self.exit_code

        extra_kwargs = {}
        if os.name != "nt":
            # Restore default handling of SIGPIPE.  See https://bugs.python.org/issue1652.
            extra_kwargs["preexec_fn"] = lambda: signal.signal(
                signal.SIGPIPE, signal.SIG_DFL
            )

        # Do not respond to ctrl-c while this is running. Users will expect this to go
        # to the fix script. Make sure we restore the signal handler afterward
        # We could have done this with tcsetpgrp and the like, but it also leads to
        # weird behavior with ctrl-z when the processes are in different pgroups
        # (to set the foreground job) and both processes don't suspend.
        with self._disable_signal_handlers():
            args = [self._path]
            args.extend(self._argv[1:])
            proc = subprocess.Popen(args, env=self._envp, cwd=self._cwd, **extra_kwargs)
            proc.wait()

        # If the script failed, return a different code to indicate that
        if proc.returncode != ExitCode.SUCCESS:
            exit_code = ExitCode.FIX_FAILED
        return exit_code


class BuckStatusReporter(object):

    """ Add custom logic to log Buck completion statuses or errors including
    critical ones like JVM crashes and OOMs. This object is fully mutable with
    all fields optional which get populated on the go. Only safe operations
    are allowed in the reporter except for report() function which can throw
    """

    def __init__(self, argv):
        self.argv = argv
        self.build_id = None
        self.buck_version = None
        self.is_buckd = False
        self.no_buckd_reason = None
        self.status_message = None
        self.repository = None
        self.start_time = time.time()

    def report(self, exit_code):
        """ Add custom code here to track Buck invocations """
        pass


class JvmCrashLogger(object):
    def __init__(self, buck_tool, project_root):
        self._buck_tool = buck_tool
        self._project_root = project_root

    def __enter__(self):
        self.jvm_errors_before = self._get_jvm_errors()

    def __exit__(self, type, value, traceback):
        new_errors = self._get_jvm_errors() - self.jvm_errors_before
        if new_errors:
            self._log_jvm_errors(new_errors)

    def _get_jvm_errors(self):
        return set(glob.glob(os.path.join(self._project_root, "hs_err_pid*.log")))

    @staticmethod
    def _format_jvm_errors(fp):
        errors = []
        keep = False
        for line in fp:
            if line.startswith("Stack:"):
                keep = True
            if line.startswith("---------------  P R O C E S S  ---------------"):
                break
            if keep:
                errors.append(line)
        message = "JVM native crash: " + (errors[2] if len(errors) > 2 else "")
        return message, "".join(errors)

    def _log_jvm_errors(self, errors):
        for file in errors:
            with open(file, "r") as f:
                message, loglines = self._format_jvm_errors(f)
                logging.error(loglines)


class BuckTool(object):
    def __init__(self, buck_project, buck_reporter):
        self._reporter = buck_reporter
        self._package_info = self._get_package_info()
        self._init_timestamp = int(round(time.time() * 1000))
        self._command_line = CommandLineArgs(sys.argv)
        self._buck_project = buck_project
        self._tmp_dir = platform_path(buck_project.tmp_dir)
        self._fake_buck_version = os.environ.get("BUCK_FAKE_VERSION")
        if self._fake_buck_version:
            logging.info("Using fake buck version: {}".format(self._fake_buck_version))

        self._pathsep = os.pathsep
        if sys.platform == "cygwin":
            self._pathsep = ";"

    def _get_package_info(self):
        raise NotImplementedError()

    def _has_resource(self, resource):
        """Check whether the given resource exists."""
        raise NotImplementedError()

    def _get_resource(self, resource):
        """Return an on-disk path to the given resource.

        This may cause implementations to unpack the resource at this point.
        """
        raise NotImplementedError()

    def _get_resource_lock_path(self):
        """Return the path to the file used to determine if another buck process is using the same
        resources folder (used for cleanup coordination).
        """
        raise NotImplementedError()

    def _get_buck_version_uid(self):
        if self._fake_buck_version:
            return self._fake_buck_version
        return self._package_info["version"]

    def _get_buck_version_timestamp(self):
        return self._package_info["timestamp"]

    def _get_buck_git_commit(self):
        raise NotImplementedError()

    def _get_buck_repo_dirty(self):
        return self._package_info["is_dirty"]

    def get_buck_compiled_java_version(self):
        return self._package_info["java_version"]

    def _get_bootstrap_classpath(self):
        raise NotImplementedError()

    def _get_buckfilesystem_classpath(self):
        raise NotImplementedError()

    def _get_java_classpath(self):
        raise NotImplementedError()

    def _get_buck_binary_hash(self):
        """
        Returns Buck binary hash

        This hash reflects the code that can affect the content of artifacts.
        """

        raise NotImplementedError()

    def _unpack_modules(self):
        raise NotImplementedError()

    def _get_extra_java_args(self):
        return []

    def _get_exported_resources(self):
        return EXPORTED_RESOURCES

    @property
    def _use_buckd(self):
        return not os.environ.get("NO_BUCKD") or os.environ.get("NO_BUCKD") == "0"

    def _environ_for_buck(self):
        env = os.environ.copy()

        classpath = str(self._get_bootstrap_classpath())
        # On Java 9 and higher, the BuckFileSystem jar gets loaded via the bootclasspath and doesn't
        # need to be added here.
        if self.get_buck_compiled_java_version() < 9:
            classpath += os.pathsep + str(self._get_buckfilesystem_classpath())

        env["CLASSPATH"] = classpath
        env["BUCK_CLASSPATH"] = str(self._get_java_classpath())
        env["BUCK_TTY"] = str(int(sys.stdin.isatty()))
        if os.name == "posix":
            # It will fallback on default on Windows platform.
            try:
                # Get the number of columns in the terminal.
                with open(os.devnull, "w") as devnull:
                    # Make sure we get a string back in py3, but encoding isn't
                    # valid in py2, so don't send in that case
                    stty_size_str = (
                        check_output(["stty", "size"], stderr=devnull)
                        .decode("utf-8")
                        .strip()
                    )
                    stty_size = stty_size_str.split(" ")
                    if len(stty_size) >= 2:
                        env["BUCK_TERM_COLUMNS"] = stty_size[1]
            except CalledProcessError:
                # If the call to tput fails, we use the default.
                pass
        return env

    def _handle_isolation_args(self, args):
        try:
            pos = args.index("--isolation_prefix")
            # Allow for the argument to --isolation_prefix
            if (pos + 1) < len(args):
                new_args = args[:pos] + args[pos + 2 :]
                new_args.append("--config")
                new_args.append(
                    "buck.base_buck_out_dir={0}".format(
                        self._buck_project.get_buck_out_relative_dir()
                    )
                )
                return new_args
            else:
                return args  # buck will error out on unrecognized option
        except ValueError:
            return args

    def _handle_buck_fix_args(self, argv, post_run_files):
        additional_args = ["--command-args-file", post_run_files.command_args_file]
        insert_idx = 1
        # Cover the case where someone runs `buck --isolation_prefix foo`, which would
        # make the first arg `--config`, not a subcommand
        if len(argv) and argv[0].startswith("--"):
            insert_idx = 0

        ret = argv[:insert_idx] + additional_args + argv[insert_idx:]
        return ret

    def _add_args(self, argv, args, post_run_files):

        self._adjust_help_args(argv, args)

        """
        Add new arguments to the end of arguments string
        But before optional arguments to test runner ("--")
        """
        if len(args) == 0:
            return self._handle_buck_fix_args(
                self._handle_isolation_args(argv), post_run_files
            )

        try:
            pos = argv.index("--")
        except ValueError:
            # "--" not found, just add to the end of the list
            pos = len(argv)

        return self._handle_buck_fix_args(
            self._handle_isolation_args(argv[:pos] + args + argv[pos:]), post_run_files
        )

    def _adjust_help_args(self, argv, args):
        # allow --help right after "buck" then followed by subcommand, in order for java code
        # to process this as help, move --help to after subcommand
        if len(argv) and argv[0] == "--help":
            del argv[0]
            args.append("--help")
        else:
            # '-h' need to be replaced by '--help' for java code to process as help
            try:
                pos_h = argv.index("-h")
            except ValueError:
                pos_h = -1
            if pos_h >= 0:
                try:
                    # don't do the replacement if '-h' is after '--', it's for external runner
                    pos_ex = argv.index("--")
                except ValueError:
                    pos_ex = -1
                if pos_ex < 0 or pos_h < pos_ex:
                    del argv[pos_h]
                    args.append("--help")

    def _add_args_from_env(self, argv, post_run_files):
        """
        Implicitly add command line arguments based on environmental variables. This is a bad
        practice and should be considered for infrastructure / debugging purposes only
        """
        args = []

        if os.environ.get("BUCK_NO_CACHE") == "1" and "--no-cache" not in argv:
            args.append("--no-cache")
        if os.environ.get("BUCK_CACHE_READONLY") == "1":
            args.append("-c")
            args.append("cache.http_mode=readonly")
        return self._add_args(argv, args, post_run_files)

    def _run_with_nailgun(self, java_path, argv, env, post_run_files):
        """
        Run the command using nailgun.  If the daemon is busy, block until it becomes free.
        """
        exit_code = 2
        busy_diagnostic_displayed = False
        while exit_code == 2:
            try:
                with NailgunConnection(
                    self._buck_project.get_buckd_transport_address(),
                    cwd=self._buck_project.root,
                ) as c:
                    now = int(round(time.time() * 1000))
                    env["BUCK_PYTHON_SPACE_INIT_TIME"] = str(now - self._init_timestamp)
                    exit_code = c.send_command(
                        "com.facebook.buck.cli.MainWithNailgun",
                        self._add_args_from_env(argv, post_run_files),
                        env=env,
                        cwd=self._buck_project.root,
                    )
                    if exit_code == 2:
                        env["BUCK_BUILD_ID"] = str(uuid.uuid4())
                        if busy_diagnostic_displayed:
                            sys.stderr.write(".")
                            sys.stderr.flush()
                        else:
                            logging.info(
                                "You can use 'buck kill' to kill buck "
                                + "if you suspect buck is stuck."
                            )
                            busy_diagnostic_displayed = True
                            env["BUCK_BUSY_DISPLAYED"] = "1"
                            sys.stderr.write("Waiting for Buck Daemon to become free")
                            sys.stderr.flush()

                        time.sleep(3)
            except NailgunException as nex:
                if nex.code == NailgunException.CONNECTION_BROKEN:
                    message = (
                        "Connection is lost to Buck daemon! This usually indicates that"
                        + " daemon experienced an unrecoverable error. Here is what you can do:\n"
                        + " - check if the machine has enough disk space and filesystem is"
                        + " accessible\n"
                        + " - check if the machine does not run out of physical memory\n"
                        + " - try to run Buck in serverless mode:"
                        + " buck kill && NO_BUCKD=1 buck <command>\n"
                    )
                    transport_address = self._buck_project.get_buckd_transport_address()
                    if not transport_address.startswith("local:"):
                        message += (
                            " - check if connection specified by "
                            + transport_address
                            + " is stable\n"
                        )

                    raise BuckDaemonErrorException(message)
                else:
                    raise nex

        return exit_code

    def _run_without_nailgun(self, java_path, argv, env, post_run_files):
        """
        Run the command by directly invoking `java` (rather than by sending a command via nailgun)
        """
        command = ["buck"]
        extra_default_options = [
            "-Dbuck.is_buckd=false",
            "-Djava.io.tmpdir={0}".format(self._tmp_dir),
            "-Dfile.encoding=UTF-8",
            "-XX:SoftRefLRUPolicyMSPerMB=0",
            "-XX:+UseG1GC",
        ]
        command.extend(
            self._get_java_args(self._get_buck_version_uid(), extra_default_options)
        )
        command.append("com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper")
        command.append("com.facebook.buck.cli.MainWithoutNailgun")
        command.extend(self._add_args_from_env(argv, post_run_files))
        now = int(round(time.time() * 1000))
        env["BUCK_PYTHON_SPACE_INIT_TIME"] = str(now - self._init_timestamp)
        with Tracing("buck", args={"command": command}):
            return subprocess.call(
                command, cwd=self._buck_project.root, env=env, executable=java_path
            )

    def _execute_command_and_maybe_run_target(self, run_fn, java_path, env, argv):
        """
        Run a buck command using the specified `run_fn`.
        If the command writes out to --command-args-file, return a callable that
        executes the program in the --command-args-file
        """
        with Tracing("buck", args={"command": sys.argv[1:]}):
            if os.name == "nt":
                # Windows now support the VT100 sequences that are used by the Superconsole, but our
                # process must opt into this behavior to enable it.

                import ctypes

                kernel32 = ctypes.windll.kernel32

                STD_OUTPUT_HANDLE = -11
                ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004

                # These calls can fail because we're not output to a terminal or we're not on a new
                # enough version of Windows. That's fine because we fail silently, and the
                # Superconsole wasn't going to work anyways.
                handle = kernel32.GetStdHandle(STD_OUTPUT_HANDLE)
                console_mode = ctypes.c_uint(0)

                if kernel32.GetConsoleMode(handle, ctypes.byref(console_mode)):
                    kernel32.SetConsoleMode(
                        handle, console_mode.value | ENABLE_VIRTUAL_TERMINAL_PROCESSING
                    )

            argv = argv[1:]

            # The argsfile can go in the project tmpdir as we read it back immediately,
            # the fix spec file needs to be somewhere else, as we cleanup the tmpdir
            # before we run the actual fix script, and we need to make sure that the
            # build details are not deleted before that.
            with MovableTemporaryFile(dir=self._tmp_dir) as argsfile:
                argsfile.close()

                post_run_files = PostRunFiles(command_args_file=argsfile.name)
                exit_code = run_fn(java_path, argv, env, post_run_files)
                if os.path.getsize(argsfile.name) == 0:
                    # No file was requested to be run by the daemon. Exit normally.
                    return ExitCodeCallable(exit_code)

                with open(argsfile.name, "r") as reopened_argsfile:
                    cmd = json.load(reopened_argsfile)
                path = cmd["path"].encode("utf8")
                argv = [arg.encode("utf8") for arg in cmd["argv"]]
                envp = {
                    k.encode("utf8"): v.encode("utf8") for k, v in cmd["envp"].items()
                }
                cwd = cmd["cwd"].encode("utf8")
                if cmd["is_fix_script"]:
                    return ExecuteFixScript(exit_code, path, argv, envp, cwd)
                else:
                    return ExecuteTarget(exit_code, path, argv, envp, cwd)

    def launch_buck(self, build_id, client_cwd, java_path, argv):
        with Tracing("BuckTool.launch_buck"):
            with JvmCrashLogger(self, self._buck_project.root):
                self._reporter.build_id = build_id

                try:
                    repository = self._get_repository()
                    self._reporter.repository = repository
                except Exception as e:
                    # _get_repository() is only for reporting,
                    # so skip on error
                    logging.warning("Failed to get repo name: " + str(e))

                if (
                    self._command_line.command == "clean"
                    and not self._command_line.is_help()
                ):
                    self.kill_buckd()

                buck_version_uid = self._get_buck_version_uid()
                self._reporter.buck_version = buck_version_uid

                if self._command_line.is_version():
                    print("buck version {}".format(buck_version_uid))
                    return ExitCodeCallable(ExitCode.SUCCESS)

                use_buckd = self._use_buckd
                if not use_buckd:
                    logging.warning("Not using buckd because NO_BUCKD is set.")
                    self._reporter.no_buckd_reason = "explicit"

                if use_buckd and self._command_line.is_help():
                    use_buckd = False
                    self._reporter.no_buckd_reason = "help"

                if use_buckd:
                    has_watchman = bool(which("watchman"))
                    if not has_watchman:
                        use_buckd = False
                        self._reporter.no_buckd_reason = "watchman"
                        logging.warning(
                            "Not using buckd because watchman isn't installed."
                        )

                if use_buckd:
                    with exclusive_lock(
                        self._buck_project.get_section_lock_path("buckd_start_stop"),
                        wait=True,
                    ):
                        need_start = True
                        running_version = self._buck_project.get_running_buckd_version()
                        running_jvm_args = (
                            self._buck_project.get_running_buckd_jvm_args()
                        )
                        jvm_args = self._get_java_args(buck_version_uid)
                        if running_version is None:
                            logging.info("Starting new Buck daemon...")
                        elif running_version != buck_version_uid:
                            logging.info(
                                "Restarting Buck daemon because Buck version has "
                                "changed..."
                            )
                        elif not self._is_buckd_running():
                            logging.info(
                                "Unable to connect to Buck daemon, restarting it..."
                            )
                        elif jvm_args != running_jvm_args:
                            logging.info(
                                "Restarting Buck daemon because JVM args have "
                                "changed..."
                            )
                        else:
                            need_start = False

                        if need_start:
                            self.kill_buckd()
                            if not self.launch_buckd(
                                java_path, jvm_args, buck_version_uid=buck_version_uid
                            ):
                                use_buckd = False
                                self._reporter.no_buckd_reason = "daemon_failure"
                                logging.warning(
                                    "Not using buckd because daemon failed to start."
                                )

                env = self._environ_for_buck()
                env["BUCK_BUILD_ID"] = build_id
                env["BUCK_CLIENT_PWD"] = client_cwd

                self._reporter.is_buckd = use_buckd
                run_fn = (
                    self._run_with_nailgun if use_buckd else self._run_without_nailgun
                )

                self._unpack_modules()

                exit_code_callable = self._execute_command_and_maybe_run_target(
                    run_fn, java_path, env, argv
                )

                # Most shells return process termination with signal as
                # 128 + N, where N is the signal. However Python's subprocess
                # call returns them as negative numbers. Buck binary protocol
                # uses shell's convention, so convert
                if exit_code_callable.exit_code < 0:
                    exit_code_callable.exit_code = 128 + (
                        -1 * exit_code_callable.exit_code
                    )
                return exit_code_callable


    def launch_buckd(self, java_path, jvm_args, buck_version_uid=None):
        with Tracing("BuckTool.launch_buckd"):
            setup_watchman_watch()
            if buck_version_uid is None:
                buck_version_uid = self._get_buck_version_uid()
            self._buck_project.create_buckd_dir()
            # Override self._tmp_dir to a long lived directory.
            buckd_tmp_dir = self._buck_project.create_buckd_tmp_dir()
            ngserver_output_path = os.path.join(buckd_tmp_dir, "ngserver-out")

            """
            Use SoftRefLRUPolicyMSPerMB for immediate GC of javac output.
            Set timeout to 60s (longer than the biggest GC pause seen for a 2GB
            heap) and GC target to 15s. This means that the GC has to miss its
            target by 100% or many 500ms heartbeats must be missed before a client
            disconnection occurs. Specify port 0 to allow Nailgun to find an
            available port, then parse the port number out of the first log entry.
            """
            command = ["buckd"]
            extra_default_options = [
                "-Dbuck.is_buckd=true",
                "-Dbuck.buckd_launch_time_nanos={0}".format(monotonic_time_nanos()),
                "-Dfile.encoding=UTF-8",
                "-XX:MaxGCPauseMillis={0}".format(GC_MAX_PAUSE_TARGET),
                "-XX:SoftRefLRUPolicyMSPerMB=0",
                # Stop Java waking up every 50ms to collect thread
                # statistics; doing it once every five seconds is much
                # saner for a long-lived daemon.
                "-XX:PerfDataSamplingInterval=5000",
                # Do not touch most signals
                "-Xrs",
                # Likewise, waking up once per second just in case
                # there's some rebalancing to be done is silly.
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:GuaranteedSafepointInterval=5000",
                "-Djava.io.tmpdir={0}".format(buckd_tmp_dir),
                "-Dcom.facebook.nailgun.NGServer.outputPath={0}".format(
                    ngserver_output_path
                ),
                "-XX:+UseG1GC",
                "-XX:MaxHeapFreeRatio=40",
            ]

            command.extend(extra_default_options)
            command.extend(jvm_args)
            command.append("com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper")
            command.append("com.facebook.buck.cli.BuckDaemon")
            command.append(self._buck_project.get_buckd_transport_address())
            command.append("{0}".format(BUCKD_CLIENT_TIMEOUT_MILLIS))

            buckd_transport_file_path = (
                self._buck_project.get_buckd_transport_file_path()
            )
            if os.name == "nt":
                # https://msdn.microsoft.com/en-us/library/windows/desktop/ms684863.aspx#DETACHED_PROCESS
                DETACHED_PROCESS = 0x00000008
                creationflags = DETACHED_PROCESS
                # do not redirect output for Windows as it deadlocks
                stdin = None
                stdout = None
                stderr = None
                close_fds = True
            else:
                """
                Change the process group of the child buckd process so that when this
                script is interrupted, it does not kill buckd.
                """
                creationflags = 0
                stdin = open(os.devnull, mode="r")
                stdout = open(
                    self._buck_project.get_buckd_stdout(), mode="w+b", buffering=0
                )
                stderr = open(
                    self._buck_project.get_buckd_stderr(), mode="w+b", buffering=0
                )
                close_fds = False

            process = subprocess.Popen(
                command,
                executable=java_path,
                cwd=self._buck_project.root,
                env=self._environ_for_buck(),
                creationflags=creationflags,
                close_fds=close_fds,
                stdin=stdin,
                stdout=stdout,
                stderr=stderr,
            )

            self._buck_project.save_buckd_version(buck_version_uid)
            self._buck_project.save_buckd_jvm_args(
                self._get_java_args(buck_version_uid)
            )

            # Give Java some time to create the listening socket.

            wait_seconds = 0.01
            repetitions = int(BUCKD_STARTUP_TIMEOUT_MILLIS / 1000.0 / wait_seconds)
            for _idx in range(repetitions):
                if transport_exists(buckd_transport_file_path):
                    break
                time.sleep(wait_seconds)

            if not transport_exists(buckd_transport_file_path):
                return False

            returncode = process.poll()

            # If the process has exited then daemon failed to start
            if returncode is not None:
                return False

            # Save pid of running daemon
            self._buck_project.save_buckd_pid(process.pid)

            logging.info("Buck daemon started.")

            return True

    def _get_repository(self):
        arcconfig = os.path.join(self._buck_project.root, ".arcconfig")
        if os.path.isfile(arcconfig):
            with open(arcconfig, "r") as fp:
                return json.load(fp).get("project_id", None)
        return os.path.basename(self._buck_project.root)


    def kill_buckd(self):
        with Tracing("BuckTool.kill_buckd"), exclusive_lock(
            self._buck_project.get_section_lock_path("buckd_kill"), wait=True
        ):
            buckd_transport_file_path = (
                self._buck_project.get_buckd_transport_file_path()
            )
            wait_for_termination = False
            if transport_exists(buckd_transport_file_path):
                logging.debug("Shutting down buck daemon.")
                try:
                    with NailgunConnection(
                        self._buck_project.get_buckd_transport_address(),
                        cwd=self._buck_project.root,
                    ) as c:
                        c.send_command("ng-stop")
                    wait_for_termination = True
                except NailgunException as e:
                    if e.code not in (
                        NailgunException.CONNECT_FAILED,
                        NailgunException.CONNECTION_BROKEN,
                        NailgunException.UNEXPECTED_CHUNKTYPE,
                    ):
                        raise BuckToolException(
                            "Unexpected error shutting down nailgun server: " + str(e)
                        )

            if os.name == "posix":
                buckd_pid = self._buck_project.get_running_buckd_pid()
                if pid_exists_posix(buckd_pid):
                    # If termination command was sent successfully then allow some time for the
                    # daemon to finish gracefully. Otherwise kill it hard.
                    if not wait_for_termination or not wait_for_process_posix(
                        buckd_pid, 5000
                    ):
                        # There is a possibility that daemon is dead for some time but pid file
                        # still exists and another process is assigned to the same pid. Ideally we
                        # should check first which process we are killing but so far let's pretend
                        # this will never happen and kill it with fire anyways.
                        try:
                            force_kill_process_posix(buckd_pid)
                        except Exception as e:
                            # In the worst case it keeps running multiple daemons simultaneously
                            # consuming memory. Not the good place to be, but let's just issue a
                            # warning for now.
                            logging.warning(
                                "Error killing running Buck daemon " + str(e)
                            )

                # it is ok to have a socket file still around, as linux domain sockets should be
                # unlinked explicitly
                if transport_exists(buckd_transport_file_path):
                    force_close_transport_posix(buckd_transport_file_path)

            elif os.name == "nt":
                # for Windows, we rely on transport to be closed to determine the process is done
                # TODO(buck_team) implement wait for process and hard kill for Windows
                for _idx in range(0, 300):
                    if not transport_exists(buckd_transport_file_path):
                        break
                    time.sleep(0.01)

            self._buck_project.clean_up_buckd()

    def _is_buckd_running(self):
        with Tracing("BuckTool._is_buckd_running"):
            transport_file_path = self._buck_project.get_buckd_transport_file_path()

            if not transport_exists(transport_file_path):
                return False

            try:
                with NailgunConnection(
                    self._buck_project.get_buckd_transport_address(),
                    stdin=None,
                    stdout=None,
                    stderr=None,
                    cwd=self._buck_project.root,
                ) as c:
                    c.send_command("ng-stats")
            except NailgunException as e:
                if e.code in (
                    NailgunException.CONNECT_FAILED,
                    NailgunException.CONNECTION_BROKEN,
                ):
                    return False
                else:
                    raise
            return True

    def _get_java_args(self, version_uid, extra_default_options=None):
        with Tracing("BuckTool._get_java_args"):
            java_args = []

            if "BUCK_DEFAULT_FILESYSTEM" not in os.environ and (
                sys.platform == "darwin" or sys.platform.startswith("linux")
            ):
                # Change default filesystem to custom filesystem for memory optimizations
                # Calls like Paths.get() would return optimized Path implementation
                if self.get_buck_compiled_java_version() >= 9:
                    # In Java 9+, the default file system provider gets initialized in the middle of
                    # loading the jar for the main class (bootstrapper.jar for Buck). Due to a
                    # potential circular dependency, we can't place BuckFileSystemProvider in
                    # bootstrapper.jar, or even in a separate jar on the regular classpath. To
                    # ensure BuckFileSystemProvider is available early enough, we add it to the JVM
                    # bootstrap classloader (not to be confused with Buck's bootstrapper) via the
                    # bootclasspath. Note that putting everything in bootstrapper.jar and putting it
                    # on the bootclasspath is problematic due to subtle classloader issues during
                    # in-process Java compilation.
                    #
                    # WARNING: The JVM appears to be sensitive about where this argument appears in
                    #          the argument list. It needs to come first, or it *sometimes* doesn't
                    #          get picked up. We don't understand exactly when or why this occurs.
                    java_args.append(
                        "-Xbootclasspath/a:" + self._get_buckfilesystem_classpath()
                    )
                java_args.append(
                    "-Djava.nio.file.spi.DefaultFileSystemProvider="
                    "com.facebook.buck.core.filesystems.BuckFileSystemProvider"
                )

            java_args.extend(
                [
                    "-Xmx{0}m".format(JAVA_MAX_HEAP_SIZE_MB),
                    "-Djava.awt.headless=true",
                    "-Djna.nosys=true",
                    "-Djava.util.logging.config.class=com.facebook.buck.cli.bootstrapper.LogConfig",
                    "-Dbuck.test_util_no_tests_dir=true",
                    "-Dbuck.version_uid={0}".format(version_uid),
                    "-Dbuck.buckd_dir={0}".format(self._buck_project.buckd_dir),
                    "-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.JavaUtilLog",
                    "-Dbuck.git_commit={0}".format(self._get_buck_version_uid()),
                    "-Dbuck.git_commit_timestamp={0}".format(
                        self._get_buck_version_timestamp()
                    ),
                    "-Dbuck.binary_hash={0}".format(self._get_buck_binary_hash()),
                    "-Dbuck.base_buck_out_dir={0}".format(
                        self._buck_project.get_buck_out_relative_dir()
                    ),
                ]
            )

            resource_lock_path = self._get_resource_lock_path()
            if resource_lock_path is not None:
                java_args.append(
                    "-Dbuck.resource_lock_path={0}".format(resource_lock_path)
                )

            for resource in self._get_exported_resources():
                if self._has_resource(resource):
                    java_args.append(
                        "-Dbuck.{0}={1}".format(
                            resource.name, self._get_resource(resource)
                        )
                    )

            if sys.platform == "darwin":
                java_args.append("-Dbuck.enable_objc=true")
                java_args.append(
                    "-Djava.library.path="
                    + os.path.dirname(self._get_resource(Resource("libjcocoa.dylib")))
                )

            if (
                "BUCK_DEBUG_MODE" in os.environ
                and os.environ.get("BUCK_DEBUG_MODE") != "0"
            ):
                suspend = "n" if os.environ.get("BUCK_DEBUG_MODE") == "2" else "y"
                port = "8888"
                java_args.append(
                    "-agentlib:jdwp=transport=dt_socket,"
                    "server=y,suspend=" + suspend + ",quiet=y,address=" + port
                )
                if suspend == "y":
                    logging.info("Waiting for debugger on port {}...".format(port))

            if (
                "BUCK_HTTP_PORT" in os.environ
                and os.environ.get("BUCK_HTTP_PORT").lstrip("+-").isdigit()
            ):
                java_args.append(
                    "-Dbuck.httpserver.port=" + os.environ.get("BUCK_HTTP_PORT")
                )

            if os.environ.get("BUCK_DEBUG_SOY"):
                java_args.append("-Dbuck.soy.debug=true")

            java_args.extend(extra_default_options or [])

            if self._buck_project.buck_javaargs:
                java_args.extend(shlex.split(self._buck_project.buck_javaargs))

            if self._buck_project.buck_javaargs_local:
                java_args.extend(shlex.split(self._buck_project.buck_javaargs_local))

            java_args.extend(self._get_extra_java_args())

            extra_java_args = os.environ.get("BUCK_EXTRA_JAVA_ARGS")
            if extra_java_args:
                java_args.extend(shlex.split(extra_java_args))

            # Remove unsupported args on newer Java versions. This is only here temporarily to
            # simplify the transition while we need to support multiple versions of the JVM.
            # TODO: Remove once Java 11 upgrade is done.
            if self.get_buck_compiled_java_version() >= 9:
                unsupported_args = set(
                    [
                        # `-verbose:gc` and `-XX:+PrintGCDetails` are technically supported, but log
                        # to stdout, which is problematic when Buck's output needs to be
                        # programmatically parsed. So we disallow them. `-Xlog:gc:/path/to/gc.log`
                        # should be used in Java 11 instead.
                        "-verbose:gc",
                        "-XX:+PrintGCDetails",
                        "-XX:+PrintGCDateStamps",
                        "-XX:+PrintGCTimeStamps",
                        "-XX:+UseParNewGC",
                    ]
                )
                stripped_args = []
                for arg in java_args:
                    if arg in unsupported_args:
                        logging.warning(
                            "Warning: Removing JVM arg `%s`, which is not supported in Java %d.",
                            arg,
                            self.get_buck_compiled_java_version(),
                        )
                    elif arg.startswith("-Xloggc"):
                        logging.warning(
                            "Warning: JVM arg `-Xloggc` is deprecated in Java %d. Replacing with `-Xlog:gc`.",
                            self.get_buck_compiled_java_version(),
                        )
                        # Though the JVM will make this replacement itself, it stupidly logs the
                        # deprecation warning to stdout, which will break programmatic parsing of
                        # Buck's output.
                        stripped_args.append(arg.replace("-Xloggc", "-Xlog:gc"))
                    else:
                        stripped_args.append(arg)
                java_args = stripped_args

            return java_args




def install_signal_handlers():
    if os.name == "posix":
        signal.signal(signal.SIGUSR1, lambda sig, frame: traceback.print_stack(frame))


def platform_path(path):
    if sys.platform != "cygwin":
        return path
    return check_output(["cygpath", "-w", path]).strip()


def setup_watchman_watch():
    with Tracing("BuckTool._setup_watchman_watch"):
        if not which("watchman"):
            message = textwrap.dedent(
                """\
                Watchman not found, please install when using buckd.
                See https://github.com/facebook/watchman for details."""
            )
            if sys.platform == "darwin":
                message += "\n(brew install watchman on OS X)"
            # Bail if watchman isn't installed as we know java's
            # FileSystemWatcher will take too long to process events.
            raise BuckToolException(message)

        logging.debug("Using watchman.")


def force_close_transport_posix(path):
    try:
        os.unlink(path)
    except OSError as e:
        # it is ok if socket did not exist
        if e.errno != errno.ENOENT:
            raise e


def transport_exists(path):
    return os.path.exists(path)


if os.name == "nt":
    import ctypes
    from ctypes.wintypes import WIN32_FIND_DATAW as WIN32_FIND_DATA

    INVALID_HANDLE_VALUE = -1
    FindFirstFile = ctypes.windll.kernel32.FindFirstFileW
    FindClose = ctypes.windll.kernel32.FindClose

    # on windows os.path.exists doesn't allow to check reliably that pipe exists
    # (os.path.exists tries to open connection to a pipe)
    def transport_exists(transport_path):
        wfd = WIN32_FIND_DATA()
        handle = FindFirstFile(transport_path, ctypes.byref(wfd))
        result = handle != INVALID_HANDLE_VALUE
        FindClose(handle)
        return result


def pid_exists_posix(pid):
    """
    Check whether process with given pid exists, does not work on Windows
    """
    if pid is None or pid <= 0:
        return False

    try:
        # does not actually kill the process
        os.kill(pid, 0)
    except OSError as err:
        if err.errno == errno.ESRCH:
            # No such process
            return False
        elif err.errno == errno.EPERM:
            # Access denied which means process still exists
            return True
        raise

    return True


def wait_for_process_posix(pid, timeout):
    """
    Wait for the process with given id to finish, up to timeout in milliseconds
    Return True if process has finished, False otherwise
    """

    logging.debug(
        "Waiting for process (pid="
        + str(pid)
        + ") to terminate for "
        + str(timeout)
        + " ms..."
    )

    # poll 10 times a second
    for _idx in range(0, int(timeout / 100)):
        if not pid_exists_posix(pid):
            return True
        time.sleep(0.1)

    return False


def force_kill_process_posix(pid):
    """
    Hard kill the process using process id
    """
    logging.debug("Sending SIGTERM to process (pid=" + str(pid) + ")")
    os.kill(pid, signal.SIGTERM)

    # allow up to 5 seconds for the process to react to termination signal, then shoot it
    # in the head
    if not wait_for_process_posix(pid, 5000):
        logging.debug("Sending SIGKILL to process (pid=" + str(pid) + ")")
        os.kill(pid, signal.SIGKILL)
