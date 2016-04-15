"""Simple benchmark to compare the speed of scandir.walk() with os.walk()."""

import optparse
import os
import stat
import sys
import timeit

import warnings
with warnings.catch_warnings(record=True):
    import scandir

DEPTH = 4
NUM_DIRS = 5
NUM_FILES = 50


def os_walk_pre_35(top, topdown=True, onerror=None, followlinks=False):
    """Pre Python 3.5 implementation of os.walk() that doesn't use scandir."""
    islink, join, isdir = os.path.islink, os.path.join, os.path.isdir

    try:
        names = os.listdir(top)
    except OSError as err:
        if onerror is not None:
            onerror(err)
        return

    dirs, nondirs = [], []
    for name in names:
        if isdir(join(top, name)):
            dirs.append(name)
        else:
            nondirs.append(name)

    if topdown:
        yield top, dirs, nondirs
    for name in dirs:
        new_path = join(top, name)
        if followlinks or not islink(new_path):
            for x in os_walk_pre_35(new_path, topdown, onerror, followlinks):
                yield x
    if not topdown:
        yield top, dirs, nondirs


def create_tree(path, depth=DEPTH):
    """Create a directory tree at path with given depth, and NUM_DIRS and
    NUM_FILES at each level.
    """
    os.mkdir(path)
    for i in range(NUM_FILES):
        filename = os.path.join(path, 'file{0:03}.txt'.format(i))
        with open(filename, 'wb') as f:
            f.write(b'foo')
    if depth <= 1:
        return
    for i in range(NUM_DIRS):
        dirname = os.path.join(path, 'dir{0:03}'.format(i))
        create_tree(dirname, depth - 1)


def get_tree_size(path):
    """Return total size of all files in directory tree at path."""
    size = 0
    try:
        for entry in scandir.scandir(path):
            if entry.is_symlink():
                pass
            elif entry.is_dir():
                size += get_tree_size(os.path.join(path, entry.name))
            else:
                size += entry.stat().st_size
    except OSError:
        pass
    return size


def benchmark(path, get_size=False):
    sizes = {}

    if get_size:
        def do_os_walk():
            size = 0
            for root, dirs, files in os.walk(path):
                for filename in files:
                    fullname = os.path.join(root, filename)
                    st = os.lstat(fullname)
                    if not stat.S_ISLNK(st.st_mode):
                        size += st.st_size
            sizes['os_walk'] = size

        def do_scandir_walk():
            sizes['scandir_walk'] = get_tree_size(path)

    else:
        def do_os_walk():
            for root, dirs, files in os.walk(path):
                pass

        def do_scandir_walk():
            for root, dirs, files in scandir.walk(path):
                pass

    # Run this once first to cache things, so we're not benchmarking I/O
    print("Priming the system's cache...")
    do_scandir_walk()

    # Use the best of 3 time for each of them to eliminate high outliers
    os_walk_time = 1000000
    scandir_walk_time = 1000000
    N = 3
    for i in range(N):
        print('Benchmarking walks on {0}, repeat {1}/{2}...'.format(
            path, i + 1, N))
        os_walk_time = min(os_walk_time, timeit.timeit(do_os_walk, number=1))
        scandir_walk_time = min(scandir_walk_time,
                                timeit.timeit(do_scandir_walk, number=1))

    if get_size:
        if sizes['os_walk'] == sizes['scandir_walk']:
            equality = 'equal'
        else:
            equality = 'NOT EQUAL!'
        print('os.walk size {0}, scandir.walk size {1} -- {2}'.format(
            sizes['os_walk'], sizes['scandir_walk'], equality))

    print('os.walk took {0:.3f}s, scandir.walk took {1:.3f}s -- {2:.1f}x as fast'.format(
          os_walk_time, scandir_walk_time, os_walk_time / scandir_walk_time))


if __name__ == '__main__':
    usage = """Usage: benchmark.py [-h] [tree_dir]

Create a large directory tree named "benchtree" (relative to this script) and
benchmark os.walk() versus scandir.walk(). If tree_dir is specified, benchmark
using it instead of creating a tree."""
    parser = optparse.OptionParser(usage=usage)
    parser.add_option('-s', '--size', action='store_true',
                      help='get size of directory tree while walking')
    parser.add_option('-c', '--scandir', type='choice', choices=['best', 'generic', 'c', 'python', 'os'], default='best',
                      help='version of scandir() to use, default "%default"')
    options, args = parser.parse_args()

    if args:
        tree_dir = args[0]
    else:
        tree_dir = os.path.join(os.path.dirname(__file__), 'benchtree')
        if not os.path.exists(tree_dir):
            print('Creating tree at {0}: depth={1}, num_dirs={2}, num_files={3}'.format(
                tree_dir, DEPTH, NUM_DIRS, NUM_FILES))
            create_tree(tree_dir)

    if options.scandir == 'generic':
        scandir.scandir = scandir.scandir_generic
    elif options.scandir == 'c':
        if scandir.scandir_c is None:
            print("ERROR: Compiled C version of scandir not found!")
            sys.exit(1)
        scandir.scandir = scandir.scandir_c
    elif options.scandir == 'python':
        if scandir.scandir_python is None:
            print("ERROR: Python version of scandir not found!")
            sys.exit(1)
        scandir.scandir = scandir.scandir_python
    elif options.scandir == 'os':
        if not hasattr(os, 'scandir'):
            print("ERROR: Python 3.5's os.scandir() not found!")
            sys.exit(1)
        scandir.scandir = os.scandir
    elif hasattr(os, 'scandir'):
        scandir.scandir = os.scandir

    if scandir.scandir == getattr(os, 'scandir', None):
        print("Using Python 3.5's builtin os.scandir()")
    elif scandir.scandir == scandir.scandir_c:
        print('Using fast C version of scandir')
    elif scandir.scandir == scandir.scandir_python:
        print('Using slower ctypes version of scandir')
    elif scandir.scandir == scandir.scandir_generic:
        print('Using very slow generic version of scandir')
    else:
        print('ERROR: Unsure which version of scandir we are using!')
        sys.exit(1)

    if hasattr(os, 'scandir'):
        os.walk = os_walk_pre_35
        print('Comparing against pre-Python 3.5 version of os.walk()')
    else:
        print('Comparing against builtin version of os.walk()')

    benchmark(tree_dir, get_size=options.size)
