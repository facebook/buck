"""Buck agnostic utility functions.
"""

from __future__ import absolute_import, division, print_function, with_statement

import copy
import functools
import inspect
import os
import subprocess
import sys
from collections import namedtuple


def is_in_dir(filepath, directory):
    """Returns true if `filepath` is in `directory`."""
    path = os.path.abspath(filepath)
    # Make 'directory' end with '/' (os.sep) to detect that '/a/foo.py' is not in '/a/f' etc.
    directory = os.path.join(os.path.abspath(directory), "")
    return os.path.commonprefix([path, directory]) == directory


def get_caller_frame(skip=None):
    """Get the stack frame from where the function was called.

    :param list[str] skip:
    :rtype: types.FrameType
    """
    if skip is None:
        skip = []
    skip = set([__name__] + skip)
    # Look up the caller's stack frame, skipping specified names.
    frame = inspect.currentframe()
    # Use 'get' as '__name__' may not exist if 'eval' was used ('get' will return None then).
    while frame.f_globals.get("__name__") in skip:
        frame = frame.f_back

    return frame


def cygwin_adjusted_path(path):
    """Convert windows paths to unix paths if running within cygwin."""
    if sys.platform == "cygwin":
        return subprocess.check_output(["cygpath", path]).rstrip()
    else:
        return path


def memoized(deepcopy=True, keyfunc=None):
    """Decorator. Caches a function's return value each time it is called.
    If called later with the same arguments, the cached value is returned
    (not reevaluated).

    Makes a defensive copy of the cached value each time it's returned,
    so callers mutating the result do not poison the cache, unless
    deepcopy is set to False.
    """

    def decorator(func):
        cache = {}

        @functools.wraps(func)
        def wrapped(*args, **kwargs):
            # poor-man's cache key; the keyword args are sorted to avoid dictionary ordering
            # issues (insertion and deletion order matters). Nested dictionaries will still cause
            # cache misses.
            if keyfunc is None:
                cache_key = repr(args) + repr(sorted(kwargs.items()))
            else:
                cache_key = keyfunc(*args, **kwargs)
            _sentinel = object()
            value = cache.get(cache_key, _sentinel)
            if value is _sentinel:
                value = func(*args, **kwargs)
                cache[cache_key] = value
            # Return a copy to ensure callers mutating the result don't poison the cache.
            if deepcopy:
                value = copy.deepcopy(value)
            return value

        wrapped._cache = cache
        return wrapped

    return decorator


Diagnostic = namedtuple("Diagnostic", ["message", "level", "source", "exception"])


def is_special(pat):
    """Whether the given pattern string contains match constructs."""
    return "*" in pat or "?" in pat or "[" in pat
