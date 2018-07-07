import contextlib
import logging
import os
import subprocess
import time

LINUX_EXIT_ZERO = ["/bin/sh", "-c", "exit", "0"]
WINDOWS_EXIT_ZERO = ["powershell", "-Command", "exit", "0"]


class ReleaseException(Exception):
    def __init__(self, format_string, *args, **kwargs):
        super().__init__(format_string.format(*args, **kwargs))


@contextlib.contextmanager
def temp_file_with_contents(dest, contents):
    """ Write out a temp file specific contents that gets deleted on context exit """
    with open(dest, "w") as fout:
        fout.write(contents)
    yield
    if os.path.exists(dest):
        os.remove(dest)


@contextlib.contextmanager
def temp_move_file(path):
    """ Move a file out of the way on entrance, and back to its original location on exit """
    bak_path = None
    if os.path.exists(path):
        bak_path = os.path.join(os.path.dirname(path), os.path.basename(path) + ".bak")
        logging.info("Temporarily moving {} to {}".format(path, bak_path))
        os.rename(path, bak_path)
    yield bak_path is None
    if bak_path:
        logging.info("Moving {} back to {}".format(bak_path, path))
        os.rename(bak_path, path)


def docker(host, command, **run_kwargs):
    """
    Run a docker command optionally on a host.

    Args:
        host: None if run locally, else a host to provided to docker -H
        command: An array of all of the parts of a docker command after 'docker'
        **run_kwargs: Additional kwargs to pass to run()
    Returns:
        result of subprocess.run()
    """
    full_command = ["docker"]
    if host:
        full_command.append("-H")
        full_command.append(host)
    full_command.extend(command)
    return run(full_command, **run_kwargs)


def run(
    command,
    cwd=None,
    capture_output=False,
    input=None,
    check=True,
    **subprocess_run_kwargs
):
    """ Wrapper for subprocess.run() that sets some sane defaults """
    logging.info("Running {} in {}".format(" ".join(command), cwd or os.getcwd()))
    if isinstance(input, str):
        input = input.encode("utf-8")
    env = os.environ.copy()
    env["HOMEBREW_NO_AUTO_UPDATE"] = "1"

    return subprocess.run(
        command,
        cwd=cwd,
        input=input,
        stdout=subprocess.PIPE if capture_output else None,
        check=check,
        env=env,
        **subprocess_run_kwargs
    )


def _copy_from_docker(host, image_tag, src_path, dest_path, exit_zero_command):
    name = "buck-build-{}".format(int(time.time()))
    docker(host, ["run", "--name", name, image_tag] + exit_zero_command)
    docker(host, ["wait", name])
    docker(host, ["cp", "{}:{}".format(name, src_path), dest_path])
    docker(host, ["rm", name])


def copy_from_docker_linux(linux_host, image_tag, src_path, dest_path):
    _copy_from_docker(linux_host, image_tag, src_path, dest_path, LINUX_EXIT_ZERO)


def copy_from_docker_windows(windows_host, image_tag, src_path, dest_path):
    _copy_from_docker(windows_host, image_tag, src_path, dest_path, WINDOWS_EXIT_ZERO)
