"""Buck agnostic utility functions.
"""

from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import with_statement

import copy
import functools
import subprocess
import sys


def cygwin_adjusted_path(path):
    """Convert windows paths to unix paths if running within cygwin."""
    if sys.platform == 'cygwin':
        return subprocess.check_output(['cygpath', path]).rstrip()
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
