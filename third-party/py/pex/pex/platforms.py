# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

from __future__ import absolute_import

import re
import sys

from pkg_resources import compatible_platforms, get_supported_platform


class Platform(object):
  class UnknownPlatformError(Exception):
    def __init__(self, platform):
      super(Platform.UnknownPlatformError, self).__init__('Unknown platform: %s' % platform)  # noqa

  # It blows my mind this code is not in distutils or distribute.
  MACOSX_VERSION_STRING = re.compile(r"macosx-(\d+)\.(\d+)-(\S+)")
  MACOSX_PLATFORM_COMPATIBILITY = {
    'i386': ('i386',),
    'ppc': ('ppc',),
    'x86_64': ('x86_64',),
    'ppc64': ('ppc64',),
    'fat': ('i386', 'ppc'),
    'intel': ('i386', 'x86_64'),
    'fat3': ('i386', 'ppc', 'x86_64'),
    'fat64': ('ppc64', 'x86_64'),
    'universal': ('i386', 'ppc', 'ppc64', 'x86_64')
  }

  @staticmethod
  def current():
    return get_supported_platform()

  @staticmethod
  def python():
    return sys.version[:3]

  @classmethod
  def compatible(cls, package, platform):
    if package is None or platform is None or package == platform:
      return True
    MAJOR, MINOR, PLATFORM = range(1, 4)
    package_match = cls.MACOSX_VERSION_STRING.match(package)
    platform_match = cls.MACOSX_VERSION_STRING.match(platform)
    if not (package_match and platform_match):
      return compatible_platforms(package, platform)
    if package_match.group(MAJOR) != platform_match.group(MAJOR):
      return False
    if int(package_match.group(MINOR)) > int(platform_match.group(MINOR)):
      return False
    package_platform = package_match.group(PLATFORM)
    if package_platform not in cls.MACOSX_PLATFORM_COMPATIBILITY:
      raise cls.UnknownPlatformError(package_platform)
    sys_platform = platform_match.group(PLATFORM)
    if sys_platform not in cls.MACOSX_PLATFORM_COMPATIBILITY:
      raise cls.UnknownPlatformError(sys_platform)
    package_compatibility = set(cls.MACOSX_PLATFORM_COMPATIBILITY[package_platform])
    system_compatibility = set(cls.MACOSX_PLATFORM_COMPATIBILITY[sys_platform])
    return bool(package_compatibility.intersection(system_compatibility))

  @staticmethod
  def version_compatible(package_py_version, py_version):
    return package_py_version is None or py_version is None or package_py_version == py_version
