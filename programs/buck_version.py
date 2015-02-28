import os
import sys
import subprocess
import tempfile

from subprocutils import check_output, which


class EmptyTempFile(object):

    def __init__(self, prefix=None, dir=None, closed=True):
        self.file, self.name = tempfile.mkstemp(prefix=prefix, dir=dir)
        if closed:
            os.close(self.file)
        self.closed = closed

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
        os.remove(self.name)

    def close(self):
        if not self.closed:
            os.close(self.file)
        self.closed = True

    def fileno(self):
        return self.file


def is_git(dirpath):
    dot_git = os.path.join(dirpath, '.git')
    return all([
        os.path.exists(dot_git),
        os.path.isdir(dot_git),
        which('git'),
        sys.platform != 'cygwin',
    ])


def is_dirty(dirpath):
    output = check_output(
        ['git', 'status', '--porcelain'],
        cwd=dirpath)
    return bool(output.strip())


def get_git_revision(dirpath):
    output = check_output(
        ['git', 'rev-parse', 'HEAD', '--'],
        cwd=dirpath)
    return output.splitlines()[0].strip()


def get_clean_buck_version(dirpath, allow_dirty=False):
    if not is_git(dirpath):
        return 'N/A'
    if allow_dirty or not is_dirty(dirpath):
        return get_git_revision(dirpath)


def get_dirty_buck_version(dirpath):
    git_tree_in = check_output(
        ['git', 'log', '-n1', '--pretty=format:%T', 'HEAD', '--'],
        cwd=dirpath).strip()

    with EmptyTempFile(prefix='buck-git-index') as index_file:
        new_environ = os.environ.copy()
        new_environ['GIT_INDEX_FILE'] = index_file.name
        subprocess.check_call(
            ['git', 'read-tree', git_tree_in],
            cwd=dirpath,
            env=new_environ)

        subprocess.check_call(
            ['git', 'add', '-u'],
            cwd=dirpath,
            env=new_environ)

        git_tree_out = check_output(
            ['git', 'write-tree'],
            cwd=dirpath,
            env=new_environ).strip()

    with EmptyTempFile(prefix='buck-version-uid-input',
                       closed=False) as uid_input:
        subprocess.check_call(
            ['git', 'ls-tree', '--full-tree', git_tree_out],
            cwd=dirpath,
            stdout=uid_input)
        return check_output(
            ['git', 'hash-object', uid_input.name],
            cwd=dirpath).strip()
