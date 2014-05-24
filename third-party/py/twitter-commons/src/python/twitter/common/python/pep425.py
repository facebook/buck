"""PEP425 handling for twitter.common.python

PEP425 (http://legacy.python.org/dev/peps/pep-0425/) describes a tagging system used to determine
whether or not a distribution's platform is compatible with the current platform.  It is the
tagging system used to describe platform compatibility for wheel files.
"""

from .platforms import Platform

from pkg_resources import get_supported_platform


class PEP425Extras(object):
  """Extensions to platform handling beyond PEP425."""

  @classmethod
  def is_macosx_platform(cls, platform):
    return platform.startswith('macosx')

  @classmethod
  def parse_macosx_tag(cls, platform_tag):
    invalid_tag = ValueError('invalid macosx tag: %s' % platform_tag)
    if not cls.is_macosx_platform(platform_tag):
      raise invalid_tag
    segments = platform_tag.split('_', 3)
    if len(segments) != 4:
      raise invalid_tag
    if segments[0] != 'macosx':
      raise invalid_tag
    try:
      major, minor = int(segments[1]), int(segments[2])
      platform = segments[3]
    except ValueError:
      raise invalid_tag
    return major, minor, platform

  @classmethod
  def iter_compatible_osx_platforms(cls, supported_platform):
    platform_major, platform_minor, platform = cls.parse_macosx_tag(supported_platform)
    platform_equivalents = set(Platform.MACOSX_PLATFORM_COMPATIBILITY.get(platform, ()))
    platform_equivalents.add(platform)
    for minor in range(platform_minor, -1, -1):
      for binary_compat in platform_equivalents:
        yield 'macosx_%s_%s_%s' % (platform_major, minor, binary_compat)

  @classmethod
  def platform_iterator(cls, platform):
    """Iterate over all compatible platform tags of a supplied platform tag.

       :param platform: the platform tag to iterate over
    """
    if cls.is_macosx_platform(platform):
      for plat in cls.iter_compatible_osx_platforms(platform):
        yield plat
    else:
      yield platform


class PEP425(object):
  INTERPRETER_TAGS = {
    'CPython': 'cp',
    'Jython': 'jy',
    'PyPy': 'pp',
    'IronPython': 'ip',
  }

  @classmethod
  def get_implementation_tag(cls, interpreter_subversion):
    return cls.INTERPRETER_TAGS.get(interpreter_subversion)

  @classmethod
  def get_version_tag(cls, interpreter_version):
    return ''.join(map(str, interpreter_version[:2]))

  @classmethod
  def translate_platform_to_tag(cls, platform):
    return platform.replace('.', '_').replace('-', '_')

  @classmethod
  def get_platform_tag(cls):
    return cls.translate_platform_to_tag(get_supported_platform())

  # TODO(user) This implementation is technically incorrect but we need to be able to
  # predict the supported tags of an interpreter that may not be on this machine or
  # of a different platform.  Alternatively we could store the manifest of supported tags
  # of a targeted platform in a file to be more correct.
  @classmethod
  def _iter_supported_tags(cls, impl, version, platform):
    """Given a set of tags, iterate over supported tags.

    :param impl: Python implementation tag e.g. cp, jy, pp.
    :param version: E.g. '26', '33'
    :param platform: Platform as from :function:`pkg_resources.get_supported_platform`,
    for example 'linux-x86_64' or 'macosx-10.4-x86_64'.
    :returns: Iterator over (pyver, abi, platform) tuples.
    """
    # Predict soabi for reasonable interpreters.  This is technically wrong but essentially right.
    abis = []
    if impl == 'cp' and version.startswith('3'):
      abis.extend(['cp%sm' % version, 'abi3'])

    major_version = int(version[0])
    minor_versions = []
    for minor in range(int(version[1]), -1, -1):
      minor_versions.append('%d%d' % (major_version, minor))

    platforms = list(PEP425Extras.platform_iterator(cls.translate_platform_to_tag(platform)))

    # interpreter specific
    for p in platforms:
      for abi in abis:
        yield ('%s%s' % (impl, version), abi, p)

    # everything else
    for p in platforms + ['any']:
      for i in ('py', impl):
        yield ('%s%d' % (i, major_version), 'none', p)
        for minor_version in minor_versions:
          yield ('%s%s' % (i, minor_version), 'none', p)

  @classmethod
  def iter_supported_tags(cls, identity, platform=get_supported_platform()):
    """Iterate over the supported tag tuples of this interpreter.

    :param identity: python interpreter identity over which tags should iterate.
    :type identity: :class:`PythonIdentity`
    :param platform: python platform over which tags should iterate, by default the current
                     platform.
    :returns: Iterator over valid PEP425 tag tuples.
    """
    impl_tag = cls.get_implementation_tag(identity.interpreter)
    vers_tag = cls.get_version_tag(identity.version)
    tag_iterator = cls._iter_supported_tags(impl_tag, vers_tag, platform)
    for tag in tag_iterator:
      yield tag
