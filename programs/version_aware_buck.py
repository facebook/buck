#!/usr/bin/env python
import os
import subprocess
import sys
import textwrap

from buck_project import BuckProject, get_file_contents_if_exists
from subprocutils import which

THIS_DIR = os.path.dirname(os.path.realpath(__file__))
sys.path.append(
    os.path.join(os.path.dirname(THIS_DIR), 'third-party', 'nailgun'))

from buck_repo import BuckRepo  # noqa
from buck_tool import JAVA_MAX_HEAP_SIZE_MB  # noqa


def get_buck_version(project):
    if os.path.exists(os.path.join(project.root, ".nobuckcheck")):
        sys.stderr.write(
            "::: '.nobuckcheck' file is present. Not updating buck.\n")
        return None

    buck_version_path = os.path.join(project.root, ".buckversion")
    buck_version = get_file_contents_if_exists(
        os.path.join(project.root, buck_version_path))
    return buck_version.strip().split(':') if buck_version else None


def revision_exists(repo, revision):
    returncode = subprocess.call(
        ['git', 'rev-parse', '--verify', revision],
        cwd=repo.buck_dir)
    return returncode == 0


def get_ant_env(max_heap_size_mb):
    ant_env = os.environ.copy()
    ant_opts = ant_env.get('ANT_OPTS', '')
    if ant_opts.find('-Xmx') == -1:
        # Adjust the max heap size if it's not already specified.
        ant_max_heap_arg = '-Xmx{0}m'.format(max_heap_size_mb)
        if ant_opts:
            ant_opts += ' '
        ant_opts += ant_max_heap_arg
        ant_env['ANT_OPTS'] = ant_opts
    return ant_env


def find_ant():
    ant = which('ant')
    if not ant:
        message = "You do not have ant on your $PATH. Cannot build Buck."
        if sys.platform == "darwin":
            message += "\nTry running 'brew install ant'."
        raise RuntimeError(message)
    return ant


def print_ant_failure_and_exit(repo, ant_log_path):
    sys.stderr.write(textwrap.dedent("""\
                ::: 'ant' failed in the buck repo at '{0}',
                ::: and 'buck' is not properly built. It will be unusable
                ::: until the error is corrected. You can check the logs
                ::: at {1} to figure out what broke.
                """.format(
        repo.buck_dir, ant_log_path)))
    if repo.is_git:
        raise RuntimeError(textwrap.dedent("""\
                ::: It is possible that running this command will fix it:
                ::: git -C "{0}" clean -xfd""".format(repo.buck_dir)))
    else:
        raise RuntimeError(textwrap.dedent("""\
                ::: It is possible that running this command will fix it:
                ::: rm -rf "{0}"/build""".format(repo.buck_dir)))


def run_ant_clean(repo, project):
    clean_log_path = os.path.join(
        project.get_buck_out_log_dir(), 'ant-clean.log')
    with open(clean_log_path, 'w') as clean_log:
        exitcode = subprocess.call(
            [find_ant(), 'clean'],
            stdout=clean_log,
            cwd=repo.buck_dir,
            env=get_ant_env(JAVA_MAX_HEAP_SIZE_MB))
        if exitcode is not 0:
            print_ant_failure_and_exit(repo, clean_log_path)


def run_ant_build(repo, project):
    ant_log_path = os.path.join(project.get_buck_out_log_dir(), 'ant.log')
    with open(ant_log_path, 'w') as ant_log:
        exitcode = subprocess.call(
            [find_ant()],
            stdout=ant_log,
            cwd=repo.buck_dir,
            env=get_ant_env(JAVA_MAX_HEAP_SIZE_MB))
        if exitcode is not 0:
            print_ant_failure_and_exit(repo, ant_log_path)


def checkout(repo, revision, branch, build_success_file):
    if not revision_exists(repo, revision):
        sys.stderr.write(
            "Required revision {0} is not available in the local repository.\n")
        git_command = ['git', 'fetch']
        git_command.extend(['--all'] if not branch else ['origin', branch])
        try:
            subprocess.check_call(
                git_command,
                stdout=sys.stderr,
                cwd=repo.buck_dir)
        except subprocess.CalledProcessError:
            raise RuntimeError("Failed to fetch Buck updates from git.")

    current_revision = repo.get_git_revision()

    if current_revision != revision:
        sys.stderr.write("Updating Buck from {0} to {1}.\n".format(
            current_revision, revision))

        try:
            subprocess.check_call(
                ['git', 'checkout', '--quiet', revision],
                cwd=repo.buck_dir)
        except subprocess.CalledProcessError:
            raise RuntimeError(
                "Failed to update Buck to revision {0}.".format(revision))

        if os.path.exists(build_success_file):
            os.remove(build_success_file)
        return True
    return False


def main(argv):
    project = BuckProject.from_current_dir()
    buck_version = get_buck_version(project)
    repo = BuckRepo(THIS_DIR, project)
    build_success_file = os.path.join(
        repo.buck_dir, "build", "successful-build")

    if repo.is_git and buck_version is not None:
        revision = buck_version[0]
        branch = buck_version[1] if len(buck_version) > 1 else None
        if checkout(repo, revision, branch, build_success_file):
            run_ant_clean(repo, project)

    if not os.path.exists(build_success_file):
        sys.stderr.write(
            "Buck does not appear to have been built -- building Buck!\n")
        run_ant_build(repo, project)
        sys.stderr.write("All done, continuing with build.\n")

    os.execvp(os.path.join(repo.buck_dir, 'bin', 'buck'), sys.argv)


if __name__ == "__main__":
    main(sys.argv)
