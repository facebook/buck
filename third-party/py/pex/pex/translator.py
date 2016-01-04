# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

from __future__ import absolute_import

import os
import traceback
from abc import abstractmethod

from .archiver import Archiver
from .common import chmod_plus_w, safe_copy, safe_mkdtemp, safe_rmtree
from .compatibility import AbstractClass
from .installer import WheelInstaller
from .interpreter import PythonInterpreter
from .package import EggPackage, Package, SourcePackage, WheelPackage
from .platforms import Platform
from .tracer import TRACER
from .util import DistributionHelper


class TranslatorBase(AbstractClass):
  """
    Translate a link into a distribution.
  """

  @abstractmethod
  def translate(self, link, into=None):
    pass


class ChainedTranslator(TranslatorBase):
  """
    Glue a sequence of Translators together in priority order.  The first Translator to resolve a
    requirement wins.
  """

  def __init__(self, *translators):
    self._translators = list(filter(None, translators))
    for tx in self._translators:
      if not isinstance(tx, TranslatorBase):
        raise ValueError('Expected a sequence of translators, got %s instead.' % type(tx))

  def translate(self, package, into=None):
    for tx in self._translators:
      dist = tx.translate(package, into=into)
      if dist:
        return dist

  def __str__(self):
    return 'ChainedTranslator(%s)' % (
        ', '.join((tx.__class__.__name__ for tx in self._translators)))


class SourceTranslator(TranslatorBase):
  @classmethod
  def run_2to3(cls, path):
    from lib2to3.refactor import get_fixers_from_package, RefactoringTool
    rt = RefactoringTool(get_fixers_from_package('lib2to3.fixes'))
    with TRACER.timed('Translating %s' % path):
      for root, dirs, files in os.walk(path):
        for fn in files:
          full_fn = os.path.join(root, fn)
          if full_fn.endswith('.py'):
            with TRACER.timed('%s' % fn, V=3):
              try:
                chmod_plus_w(full_fn)
                rt.refactor_file(full_fn, write=True)
              except IOError:
                TRACER.log('Failed to translate %s' % fn)
                TRACER.log(traceback.format_exc())

  def __init__(self,
               interpreter=PythonInterpreter.get(),
               platform=Platform.current(),
               use_2to3=False,
               installer_impl=WheelInstaller):
    self._interpreter = interpreter
    self._installer_impl = installer_impl
    self._use_2to3 = use_2to3
    self._platform = platform

  def translate(self, package, into=None):
    """From a SourcePackage, translate to a binary distribution."""
    if not isinstance(package, SourcePackage):
      return None
    if not package.local:
      raise ValueError('SourceTranslator cannot translate remote packages.')

    installer = None
    version = self._interpreter.version
    unpack_path = Archiver.unpack(package.path)
    into = into or safe_mkdtemp()

    try:
      if self._use_2to3 and version >= (3,):
        with TRACER.timed('Translating 2->3 %s' % package.name):
          self.run_2to3(unpack_path)
      installer = self._installer_impl(
          unpack_path,
          interpreter=self._interpreter,
          strict=(package.name not in ('distribute', 'setuptools')))
      with TRACER.timed('Packaging %s' % package.name):
        try:
          dist_path = installer.bdist()
        except self._installer_impl.InstallFailure as e:
          TRACER.log('Failed to install package at %s: %s' % (unpack_path, e))
          return None
        target_path = os.path.join(into, os.path.basename(dist_path))
        safe_copy(dist_path, target_path)
        target_package = Package.from_href(target_path)
        if not target_package:
          TRACER.log('Target path %s does not look like a Package.' % target_path)
          return None
        if not target_package.compatible(self._interpreter.identity, platform=self._platform):
          TRACER.log('Target package %s is not compatible with %s / %s' % (
              target_package, self._interpreter.identity, self._platform))
          return None
        return DistributionHelper.distribution_from_path(target_path)
    except Exception as e:
      TRACER.log('Failed to translate %s' % package)
      TRACER.log(traceback.format_exc())
    finally:
      if installer:
        installer.cleanup()
      if unpack_path:
        safe_rmtree(unpack_path)


class BinaryTranslator(TranslatorBase):
  def __init__(self,
               package_type,
               interpreter=PythonInterpreter.get(),
               platform=Platform.current()):
    self._package_type = package_type
    self._platform = platform
    self._identity = interpreter.identity

  def translate(self, package, into=None):
    """From a binary package, translate to a local binary distribution."""
    if not package.local:
      raise ValueError('BinaryTranslator cannot translate remote packages.')
    if not isinstance(package, self._package_type):
      return None
    if not package.compatible(identity=self._identity, platform=self._platform):
      TRACER.log('Target package %s is not compatible with %s / %s' % (
          package, self._identity, self._platform))
      return None
    into = into or safe_mkdtemp()
    target_path = os.path.join(into, package.filename)
    safe_copy(package.path, target_path)
    return DistributionHelper.distribution_from_path(target_path)


class EggTranslator(BinaryTranslator):
  def __init__(self, **kw):
    super(EggTranslator, self).__init__(EggPackage, **kw)


class WheelTranslator(BinaryTranslator):
  def __init__(self, **kw):
    super(WheelTranslator, self).__init__(WheelPackage, **kw)


class Translator(object):
  @staticmethod
  def default(platform=Platform.current(), interpreter=None):
    # TODO(wickman) Consider interpreter=None to indicate "universal" packages
    # since the .whl format can support this.
    # Also consider platform=None to require platform-inspecific packages.
    # Issue #95.
    interpreter = interpreter or PythonInterpreter.get()
    whl_translator = WheelTranslator(platform=platform, interpreter=interpreter)
    egg_translator = EggTranslator(platform=platform, interpreter=interpreter)
    source_translator = SourceTranslator(platform=platform, interpreter=interpreter)
    return ChainedTranslator(whl_translator, egg_translator, source_translator)
