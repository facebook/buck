"""Glob implementation using watchman queries."""
from util import Diagnostic, memoized
import re
import os.path
import pywatchman


COLLAPSE_SLASHES = re.compile(r'/+')


class SyncCookieState(object):
    """Process-wide state used to enable Watchman sync cookies only on the
    first query issued.
    """

    def __init__(self):
        self.use_sync_cookies = True


def format_watchman_query_params(includes, excludes, include_dotfiles, relative_root,
                                 watchman_use_glob_generator):
    match_exprs = ["allof", ["anyof", ["type", "f"], ["type", "l"]]]
    match_flags = {}

    if include_dotfiles:
        match_flags["includedotfiles"] = True
    if excludes:
        match_exprs.append(
            ["not",
             ["anyof"] +
             [["match", COLLAPSE_SLASHES.sub('/', x), "wholename", match_flags]
              for x in excludes]])

    query = {
        "relative_root": relative_root,
        "fields": ["name"],
    }

    if watchman_use_glob_generator:
        query["glob"] = includes
        # We don't have to check for existence; the glob generator only matches
        # files which exist.
    else:
        # We do have to check for existence; the path generator
        # includes files which don't exist.
        match_exprs.append("exists")

        # Explicitly pass an empty path so Watchman queries only the tree of files
        # starting at base_path.
        query["path"] = ['']
        match_exprs.append(
            ["anyof"] +
            # Collapse multiple consecutive slashes in pattern until fix in
            # https://github.com/facebook/watchman/pull/310/ is available
            [["match", COLLAPSE_SLASHES.sub('/', i), "wholename", match_flags] for i in includes])

    query["expression"] = match_exprs

    return query


def stat_results(base_path, result, diagnostics):
    statted_result = []
    for path in result:
        # We really shouldn't have to stat() every result from Watchman, but
        # occasionally it returns non-existent files.
        resolved_path = os.path.join(base_path, path)
        if os.path.exists(resolved_path):
            statted_result.append(path)
        else:
            diagnostics.append(
                Diagnostic(
                    message='Watchman query returned non-existent file: {0}'.format(
                        resolved_path),
                    level='warning',
                    source='watchman',
                    exception=None))
    return statted_result


@memoized()
def glob_watchman(includes, excludes, include_dotfiles, base_path, watchman_watch_root,
                  watchman_project_prefix, sync_cookie_state, watchman_client, diagnostics,
                  watchman_glob_stat_results, watchman_use_glob_generator):
    assert includes, "The includes argument must be a non-empty list of strings."

    if watchman_project_prefix:
        relative_root = os.path.join(watchman_project_prefix, base_path)
    else:
        relative_root = base_path
    query_params = format_watchman_query_params(
        includes, excludes, include_dotfiles, relative_root, watchman_use_glob_generator)

    # Sync cookies cause a massive overhead when issuing thousands of
    # glob queries.  Only enable them (by not setting sync_timeout to 0)
    # for the very first request issued by this process.
    if sync_cookie_state.use_sync_cookies:
        sync_cookie_state.use_sync_cookies = False
    else:
        query_params["sync_timeout"] = 0

    query = ["query", watchman_watch_root, query_params]
    try:
        res = watchman_client.query(*query)
    except pywatchman.SocketTimeout:
        # Watchman timeouts are not fatal.  Fall back on the normal glob flow.
        return None
    error_message = res.get('error')
    if error_message is not None:
        diagnostics.append(
            Diagnostic(
                message=error_message,
                level='error',
                source='watchman',
                exception=None))
    warning_message = res.get('warning')
    if warning_message is not None:
        diagnostics.append(
            Diagnostic(
                message=warning_message,
                level='warning',
                source='watchman',
                exception=None))
    result = res.get('files', [])
    if watchman_glob_stat_results:
        absolute_base_path = os.path.join(watchman_watch_root, relative_root)
        result = stat_results(absolute_base_path, result, diagnostics)
    return sorted(result)


__all__ = [glob_watchman, SyncCookieState]
