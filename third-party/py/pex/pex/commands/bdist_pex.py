import os
from distutils import log

from setuptools import Command

from pex.bin.pex import build_pex, configure_clp
from pex.common import die
from pex.variables import ENV


# Suppress checkstyle violations due to setuptools command requirements.
class bdist_pex(Command):  # noqa
  description = "create a PEX file from a source distribution"  # noqa

  user_options = [  # noqa
      ('bdist-all', None, 'pexify all defined entry points'),
      ('bdist-dir=', None, 'the directory into which pexes will be written, default: dist.'),
      ('pex-args=', None, 'additional arguments to the pex tool'),
  ]

  boolean_options = [  # noqa
    'bdist-all',
  ]

  def initialize_options(self):
    self.bdist_all = False
    self.bdist_dir = None
    self.pex_args = ''

  def finalize_options(self):
    self.pex_args = self.pex_args.split()

  def _write(self, pex_builder, target, script=None):
    builder = pex_builder.clone()

    if script is not None:
      builder.set_script(script)

    builder.build(target)

  def run(self):
    name = self.distribution.get_name()
    version = self.distribution.get_version()
    parser, options_builder = configure_clp()
    package_dir = os.path.dirname(os.path.realpath(os.path.expanduser(
        self.distribution.script_name)))

    if self.bdist_dir is None:
      self.bdist_dir = os.path.join(package_dir, 'dist')

    options, reqs = parser.parse_args(self.pex_args)

    if options.entry_point or options.script:
      die('Must not specify entry_point or script to --pex-args')

    reqs = [package_dir] + reqs

    with ENV.patch(PEX_VERBOSE=str(options.verbosity)):
      pex_builder = build_pex(reqs, options, options_builder)

    def split_and_strip(entry_point):
      console_script, entry_point = entry_point.split('=', 2)
      return console_script.strip(), entry_point.strip()

    try:
      console_scripts = dict(split_and_strip(script)
          for script in self.distribution.entry_points.get('console_scripts', []))
    except ValueError:
      console_scripts = {}

    if self.bdist_all:
      # Write all entry points into unversioned pex files.
      for script_name in console_scripts:
        target = os.path.join(self.bdist_dir, script_name)
        log.info('Writing %s to %s' % (script_name, target))
        self._write(pex_builder, target, script=script_name)
    elif name in console_scripts:
      # The package has a namesake entry point, so use it.
      target = os.path.join(self.bdist_dir, name + '-' + version + '.pex')
      log.info('Writing %s to %s' % (name, target))
      self._write(pex_builder, target, script=name)
    else:
      # The package has no namesake entry point, so build an environment pex.
      log.info('Writing environment pex into %s' % target)
      self._write(pex_builder, target, script=None)
