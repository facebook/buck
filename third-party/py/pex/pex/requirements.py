# Copyright 2015 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

import os

from .resolvable import Resolvable
from .resolver_options import ResolverOptionsBuilder


class UnsupportedLine(Exception):
  pass


def _startswith_any(line, things):
  return any(line.startswith(thing) for thing in things)


def _get_parameter(line):
  sline = line.split('=')
  if len(sline) != 2:
    sline = line.split()
  if len(sline) != 2:
    raise UnsupportedLine('Unrecognized line format: %s' % line)
  return sline[1]


class RequirementsTxtSentinel(object):
  def __init__(self, filename):
    self.filename = filename


# Process lines in the requirements.txt format as defined here:
# https://pip.pypa.io/en/latest/reference/pip_install.html#requirements-file-format
def requirements_from_lines(lines, builder=None, relpath=None):
  relpath = relpath or os.getcwd()
  builder = builder.clone() if builder else ResolverOptionsBuilder()
  to_resolve = []

  for line in lines:
    line = line.strip()
    if not line or line.startswith('#'):
      continue
    elif line.startswith('-e '):
      raise UnsupportedLine('Editable distributions not supported: %s' % line)
    elif _startswith_any(line, ('-i ', '--index-url')):
      builder.set_index(_get_parameter(line))
    elif line.startswith('--extra-index-url'):
      builder.add_index(_get_parameter(line))
    elif _startswith_any(line, ('-f ', '--find-links')):
      builder.add_repository(_get_parameter(line))
    elif line.startswith('--allow-external'):
      builder.allow_external(_get_parameter(line))
    elif line.startswith('--allow-all-external'):
      builder.allow_all_external()
    elif line.startswith('--allow-unverified'):
      builder.allow_unverified(_get_parameter(line))
    elif line.startswith('--no-index'):
      builder.clear_indices()
    elif line.startswith('--no-use-wheel'):
      builder.no_use_wheel()
    # defer the conversion of strings/files to resolvables until all options defined
    # within the current grouping of lines has been processed.
    elif _startswith_any(line, ('-r ', '--requirement')):
      path = os.path.join(relpath, _get_parameter(line))
      to_resolve.append(RequirementsTxtSentinel(path))
    else:
      to_resolve.append(line)

  resolvables = []

  for resolvable in to_resolve:
    if isinstance(resolvable, RequirementsTxtSentinel):
      resolvables.extend(requirements_from_file(resolvable.filename, builder=builder))
    else:
      try:
        resolvables.append(Resolvable.get(resolvable, builder))
      except Resolvable.Error as e:
        raise UnsupportedLine('Could not resolve line: %s (%s)' % (resolvable, e))

  return resolvables


def requirements_from_file(filename, builder=None):
  """Return a list of :class:`Resolvable` objects from a requirements.txt file.

  :param filename: The filename of the requirements.txt
  :keyword builder: (optional) The ResolverOptionsBuilder from which we should inherit
    default resolver options.
  :type builder: :class:`ResolverOptionsBuilder`
  """

  relpath = os.path.dirname(filename)
  with open(filename, 'r') as fp:
    return requirements_from_lines(fp.readlines(), builder=builder, relpath=relpath)
