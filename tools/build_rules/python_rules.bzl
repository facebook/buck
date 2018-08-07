"""Module containing Python macros."""

def interpreter_override_args():
    """Determine interpreter override arguments based on a buck config.

    For example, `--config user.buck_pex_interpreter=python2` will generate a pex
    which invokes `python2` instead of the default, `python<major>.<minor>`.

    Returns:
      a list of arguments to pass to python interpreter.
    """
    val = native.read_config("user", "buck_pex_interpreter", "")
    if val != "":
        return ["--python-shebang", "/usr/bin/env " + val]
    else:
        return []
