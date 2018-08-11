"""Provides file_alias macro."""

_DEFAULT_VISIBILITY = ["PUBLIC"]

def file_alias(name, source, visibility = None):
    """Exports a file from source in current package.

    This is useful for renaming files that are passed as resources.

    Args:
      name: output file name.
      source: path or label identifying the original file.
      visibility: targets this file should be made visible to.
    """
    visibility = visibility if visibility != None else _DEFAULT_VISIBILITY
    native.export_file(name = name, src = source, visibility = visibility)
