"""Glob implementation using mercurial manifest."""
from pathlib import _Accessor, PosixPath
from .glob_internal import glob_internal
from .util import Diagnostic, memoized
import itertools
import os.path


class DiagnosticsFileObject(object):
    def __init__(self, diagnostics):
        self.diagnostics = diagnostics

    def write(self, value):
        self.diagnostics.append(Diagnostic(
            message=value, level='warning', source='mercurial', exception=None))

    def flush(self):
        pass


class ManifestTrie(object):
    """Basic trie implementation from a hg repository manifest and working copy status

    Lazily parses path strings of children as the trie is traversed.
    """
    @classmethod
    def from_repo(cls, repo_info, project_root):
        entries = {}
        hgroot, manifest, status = repo_info
        base_path = os.path.relpath(project_root, hgroot)
        if base_path:
            base_path += '/'
        removed = set(status.removed).union(status.deleted)
        entries = (
            path[len(base_path):]
            for path in itertools.chain(manifest, status.added, status.unknown)
            if path.startswith(base_path) and path not in removed)
        return cls(entries)

    def __init__(self, entries):
        parsed = {}
        for path in filter(None, entries):
            parent, _, remainder = path.partition('/')
            parsed.setdefault(parent, []).append(remainder)
        self._entries = parsed or None  # None is a leaf node

    def listdir(self):
        return list(self._entries)

    def is_dir(self):
        return self._entries is not None

    def traverse(self, name):
        entry = self._entries[name]
        if not isinstance(entry, type(self)):
            self._entries[name] = entry = type(self)(entry)
        return entry


class ManifestAccessor(_Accessor):
    def listdir(self, path):
        return path._manifest_trie.listdir()


class ManifestPath(PosixPath):
    """Minimal Path implementation sourced from a manifest"""
    __slots__ = ('_manifest_trie',)

    def __new__(cls, *args, **kwargs):
        self = cls._from_parts(args, init=False)
        self._init()
        self._manifest_trie = kwargs.pop('manifest_trie', None)
        return self

    def _init(self):
        self._accessor = ManifestAccessor()

    def _make_child_relpath(self, part):
        child = super(ManifestPath, self)._make_child_relpath(part)
        try:
            child._manifest_trie = self._manifest_trie.traverse(part)
        except KeyError:
            child._manifest_trie = None
        return child

    def is_dir(self):
        return self._manifest_trie.is_dir()

    def is_file(self):
        return not self.is_dir()

    def exists(self):
        return self._manifest_trie is not None

    def relative_to(self, *other):
        # produce a PosixPath object again
        result = super(PosixPath, self).relative_to(*other)
        return PosixPath._from_parsed_parts(result._drv, result._root, result._parts)


def load_mercurial_repo_info(build_env, search_base, allow_safe_import):
    if not build_env.use_mercurial_glob:
        return
    for parent in itertools.chain((search_base,), search_base.parents):
        if parent.joinpath('.hg').is_dir():
            break
    else:
        # No parent found
        return
    return _load_mercurial_repo_info_for_root(build_env, parent, allow_safe_import)


@memoized(deepcopy=False, keyfunc=lambda build_env, hgroot, *a: str(hgroot))
def _load_mercurial_repo_info_for_root(build_env, hgroot, allow_safe_import):
    os.environ['HGPLAIN'] = ''  # any output should be plain
    with allow_safe_import():
        from mercurial import ui, hg
        # Mercurial imports extensions when creating the UI and accessing a new repository.
        try:
            # Explicitly load configuration
            ui_ = ui.ui.load()
        except AttributeError:
            # Older Mercurial version, configuration is loaded implicitly
            ui_ = ui.ui()
        ui_.fout = DiagnosticsFileObject(build_env.diagnostics)
        ui_.ferr = DiagnosticsFileObject(build_env.diagnostics)
        ui_.quiet = True
        repo = hg.repository(ui_, str(hgroot))
    manifest = repo['.'].manifest()
    status = repo.status(unknown=True)
    return (repo.root, manifest, status)


@memoized(
    deepcopy=False,
    keyfunc=lambda repo_info, project_root: (repo_info[0], project_root))
def _load_manifest_trie(repo_info, project_root):
    return ManifestTrie.from_repo(repo_info, project_root)


def glob_mercurial_manifest(
        includes, excludes, project_root_relative_excludes, include_dotfiles,
        search_base, project_root, repo_info):
    # pull file information from the mercurial manifest; this doesn't require
    # any filesystem access beyond access to the mercurial repository itself.

    trie = _load_manifest_trie(repo_info, project_root)
    try:
        for p in search_base.relative_to(project_root).parts:
            trie = trie.traverse(p)
    except KeyError:
        # no such path in the manifest, short-circuit
        return []
    return glob_internal(
        includes, excludes, project_root_relative_excludes, include_dotfiles,
        ManifestPath(search_base, manifest_trie=trie), project_root)
