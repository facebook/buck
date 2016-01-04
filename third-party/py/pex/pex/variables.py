# Copyright 2015 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

# Due to the PEX_ properties, disable checkstyle.
# checkstyle: noqa

import os
import sys
from contextlib import contextmanager

from .common import die

__all__ = ('ENV', 'Variables')


class Variables(object):
  """Environment variables supported by the PEX runtime."""

  @classmethod
  def process_pydoc(cls, pydoc):
    if pydoc is None:
      return 'Unknown', 'Unknown'
    pydoc = pydoc.splitlines()
    variable_type = pydoc[0]
    variable_text = ' '.join(filter(None, (line.strip() for line in pydoc[2:])))
    return variable_type, variable_text

  @classmethod
  def iter_help(cls):
    for variable_name, value in sorted(cls.__dict__.items()):
      if not variable_name.startswith('PEX_'):
        continue
      variable_type, variable_text = cls.process_pydoc(getattr(value, '__doc__'))
      yield variable_name, variable_type, variable_text

  def __init__(self, environ=None, rc='~/.pexrc', use_defaults=True):
    self._use_defaults = use_defaults
    self._environ = environ.copy() if environ else os.environ
    if not self.PEX_IGNORE_RCFILES:
      rc_values = self._from_rc(rc).copy()
      rc_values.update(self._environ)
      self._environ = rc_values

  def copy(self):
    return self._environ.copy()

  def delete(self, variable):
    self._environ.pop(variable, None)

  def set(self, variable, value):
    self._environ[variable] = str(value)

  def _from_rc(self, rc):
    ret_vars = {}
    for filename in ['/etc/pexrc', rc, os.path.join(os.path.dirname(sys.argv[0]), '.pexrc')]:
      try:
        with open(os.path.expanduser(filename)) as fh:
          rc_items = map(self._get_kv, fh)
          ret_vars.update(dict(filter(None, rc_items)))
      except IOError:
        continue
    return ret_vars

  def _get_kv(self, variable):
    kv = variable.strip().split('=')
    if len(list(filter(None, kv))) == 2:
      return kv

  def _defaulted(self, default):
    return default if self._use_defaults else None

  def _get_bool(self, variable, default=False):
    value = self._environ.get(variable)
    if value is not None:
      if value.lower() in ('0', 'false'):
        return False
      elif value.lower() in ('1', 'true'):
        return True
      else:
        die('Invalid value for %s, must be 0/1/false/true, got %r' % (variable, value))
    else:
      return self._defaulted(default)

  def _get_string(self, variable, default=None):
    return self._environ.get(variable, self._defaulted(default))

  def _get_path(self, variable, default=None):
    value = self._get_string(variable, default=default)
    if value is not None:
      return os.path.realpath(os.path.expanduser(value))

  def _get_int(self, variable, default=None):
    try:
      return int(self._environ[variable])
    except ValueError:
      die('Invalid value for %s, must be an integer, got %r' % (variable, self._environ[variable]))
    except KeyError:
      return self._defaulted(default)

  def strip_defaults(self):
    """Returns a copy of these variables but with defaults stripped.

    Any variables not explicitly set in the environment will have a value of `None`.
    """
    return Variables(environ=self.copy(), use_defaults=False)

  @contextmanager
  def patch(self, **kw):
    """Update the environment for the duration of a context."""
    old_environ = self._environ
    self._environ = self._environ.copy()
    self._environ.update(kw)
    yield
    self._environ = old_environ

  @property
  def PEX_ALWAYS_CACHE(self):
    """Boolean

    Always write PEX dependencies to disk prior to invoking regardless whether or not the
    dependencies are zip-safe.  For certain dependencies that are very large such as numpy, this
    can reduce the RAM necessary to launch the PEX.  The data will be written into $PEX_ROOT,
    which by default is $HOME/.pex.  Default: false.
    """
    return self._get_bool('PEX_ALWAYS_CACHE', default=False)

  @property
  def PEX_COVERAGE(self):
    """Boolean

    Enable coverage reporting for this PEX file.  This requires that the "coverage" module is
    available in the PEX environment.  Default: false.
    """
    return self._get_bool('PEX_COVERAGE', default=False)

  @property
  def PEX_COVERAGE_FILENAME(self):
    """Filename

    Write the coverage data to the specified filename.  If PEX_COVERAGE_FILENAME is not specified
    but PEX_COVERAGE is, coverage information will be printed to stdout and not saved.
    """
    return self._get_path('PEX_COVERAGE_FILENAME', default=None)

  @property
  def PEX_FORCE_LOCAL(self):
    """Boolean

    Force this PEX to be not-zip-safe. This forces all code and dependencies to be written into
    $PEX_ROOT prior to invocation.  This is an option for applications with static assets that
    refer to paths relative to __file__ instead of using pkgutil/pkg_resources.  Default: false.
    """
    return self._get_bool('PEX_FORCE_LOCAL', default=False)

  @property
  def PEX_IGNORE_ERRORS(self):
    """Boolean

    Ignore any errors resolving dependencies when invoking the PEX file. This can be useful if you
    know that a particular failing dependency is not necessary to run the application.  Default:
    false.
    """
    return self._get_bool('PEX_IGNORE_ERRORS', default=False)

  @property
  def PEX_INHERIT_PATH(self):
    """Boolean

    Allow inheriting packages from site-packages.  By default, PEX scrubs any packages and
    namespace packages from sys.path prior to invoking the application.  This is generally not
    advised, but can be used in situations when certain dependencies do not conform to standard
    packaging practices and thus cannot be bundled into PEX files.  Default: false.
    """
    return self._get_bool('PEX_INHERIT_PATH', default=False)

  @property
  def PEX_INTERPRETER(self):
    """Boolean

    Drop into a REPL instead of invoking the predefined entry point of this PEX. This can be
    useful for inspecting the PEX environment interactively.  It can also be used to treat the PEX
    file as an interpreter in order to execute other scripts in the context of the PEX file, e.g.
    "PEX_INTERPRETER=1 ./app.pex my_script.py".  Equivalent to setting PEX_MODULE to empty.
    Default: false.
    """
    return self._get_bool('PEX_INTERPRETER', default=False)

  @property
  def PEX_MODULE(self):
    """String

    Override the entry point into the PEX file.  Can either be a module, e.g.  'SimpleHTTPServer',
    or a specific entry point in module:symbol form, e.g.  "myapp.bin:main".
    """
    return self._get_string('PEX_MODULE', default=None)

  @property
  def PEX_PROFILE(self):
    """Boolean

    Enable application profiling.  If specified and PEX_PROFILE_FILENAME is not specified, PEX will
    print profiling information to stdout.
    """
    return self._get_path('PEX_PROFILE', default=None)

  @property
  def PEX_PROFILE_FILENAME(self):
    """Filename

    Profile the application and dump a profile into the specified filename in the standard
    "profile" module format.
    """
    return self._get_path('PEX_PROFILE_FILENAME', default=None)

  @property
  def PEX_PROFILE_SORT(self):
    """String

    Toggle the profile sorting algorithm used to print out profile columns.  Default:
    'cumulative'.
    """
    return self._get_string('PEX_PROFILE_SORT', default='cumulative')

  @property
  def PEX_PYTHON(self):
    """String

    Override the Python interpreter used to invoke this PEX.  Can be either an absolute path to an
    interpreter or a base name e.g.  "python3.3".  If a base name is provided, the $PATH will be
    searched for an appropriate match.
    """
    return self._get_string('PEX_PYTHON', default=None)

  @property
  def PEX_ROOT(self):
    """Directory

    The directory location for PEX to cache any dependencies and code.  PEX must write
    not-zip-safe eggs and all wheels to disk in order to activate them.  Default: ~/.pex
    """
    return self._get_path('PEX_ROOT', default=None)

  @property
  def PEX_PATH(self):
    """A set of one or more PEX files

    Merge the packages from other PEX files into the current environment.  This allows you to
    do things such as create a PEX file containing the "coverage" module or create PEX files
    containing plugin entry points to be consumed by a main application.  Paths should be
    specified in the same manner as $PATH, e.g. PEX_PATH=/path/to/pex1.pex:/path/to/pex2.pex
    and so forth.
    """
    return self._get_string('PEX_PATH', default='')

  @property
  def PEX_SCRIPT(self):
    """String

    The script name within the PEX environment to execute.  This must either be an entry point as
    defined in a distribution's console_scripts, or a script as defined in a distribution's
    scripts section.  While Python supports any script including shell scripts, PEX only supports
    invocation of Python scripts in this fashion.
    """
    return self._get_string('PEX_SCRIPT', default=None)

  @property
  def PEX_TEARDOWN_VERBOSE(self):
    """Boolean

    Enable verbosity for when the interpreter shuts down.  This is mostly only useful for
    debugging PEX itself.  Default: false.
    """
    return self._get_bool('PEX_TEARDOWN_VERBOSE', default=False)

  @property
  def PEX_VERBOSE(self):
    """Integer

    Set the verbosity level of PEX debug logging.  The higher the number, the more logging, with 0
    being disabled.  This environment variable can be extremely useful in debugging PEX
    environment issues.  Default: 0
    """
    return self._get_int('PEX_VERBOSE', default=0)

  # TODO(wickman) Remove and push into --flags.  #94
  @property
  def PEX_HTTP_RETRIES(self):
    """Integer

    The number of HTTP retries when performing dependency resolution when building a PEX file.
    Default: 5.
    """
    return self._get_int('PEX_HTTP_RETRIES', default=5)

  @property
  def PEX_IGNORE_RCFILES(self):
    """Boolean

    Explicitly disable the reading/parsing of pexrc files (~/.pexrc). Default: false.
    """
    return self._get_bool('PEX_IGNORE_RCFILES', default=False)


# Global singleton environment
ENV = Variables()
