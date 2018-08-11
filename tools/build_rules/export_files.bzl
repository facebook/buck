"""Provides export_files macro."""

_DEFAULT_VISIBILITY = ["PUBLIC"]

def export_files(files, visibility = None, licenses = None):
    """Exports passed files to other packages.

    Args:
      files: file paths from this package that should be exported.
      visibility: targets provided paths should be visible to.
      licenses: license files for the exported files.
    """
    visibility = visibility or _DEFAULT_VISIBILITY
    for filename in files:
        native.export_file(name = filename, visibility = visibility, licenses = licenses)
