# Copyright 2014 Pants project contributors (see CONTRIBUTORS.md).
# Licensed under the Apache License, Version 2.0 (see LICENSE).

from __future__ import print_function

from pkg_resources import safe_name

from .crawler import Crawler
from .fetcher import Fetcher, PyPIFetcher
from .http import Context
from .installer import EggInstaller, WheelInstaller
from .iterator import Iterator
from .package import EggPackage, SourcePackage, WheelPackage
from .sorter import Sorter
from .translator import ChainedTranslator, EggTranslator, SourceTranslator, WheelTranslator


class ResolverOptionsInterface(object):
  def get_context(self):
    raise NotImplemented

  def get_crawler(self):
    raise NotImplemented

  def get_sorter(self):
    raise NotImplemented

  def get_translator(self, interpreter, platform):
    raise NotImplemented

  def get_iterator(self):
    raise NotImplemented


class ResolverOptionsBuilder(object):
  """A helper that processes options into a ResolverOptions object.

  Used by command-line and requirements.txt processors to configure a resolver.
  """

  def __init__(self,
               fetchers=None,
               allow_all_external=False,
               allow_external=None,
               allow_unverified=None,
               precedence=None,
               context=None):
    self._fetchers = fetchers if fetchers is not None else [PyPIFetcher()]
    self._allow_all_external = allow_all_external
    self._allow_external = allow_external if allow_external is not None else set()
    self._allow_unverified = allow_unverified if allow_unverified is not None else set()
    self._precedence = precedence if precedence is not None else Sorter.DEFAULT_PACKAGE_PRECEDENCE
    self._context = context or Context.get()

  def clone(self):
    return ResolverOptionsBuilder(
        fetchers=self._fetchers[:],
        allow_all_external=self._allow_all_external,
        allow_external=self._allow_external.copy(),
        allow_unverified=self._allow_unverified.copy(),
        precedence=self._precedence[:],
        context=self._context,
    )

  def add_index(self, index):
    fetcher = PyPIFetcher(index)
    if fetcher not in self._fetchers:
      self._fetchers.append(fetcher)
    return self

  def set_index(self, index):
    self._fetchers = [PyPIFetcher(index)]
    return self

  def add_repository(self, repo):
    fetcher = Fetcher([repo])
    if fetcher not in self._fetchers:
      self._fetchers.append(fetcher)
    return self

  def clear_indices(self):
    self._fetchers = [fetcher for fetcher in self._fetchers if not isinstance(fetcher, PyPIFetcher)]
    return self

  def allow_all_external(self):
    self._allow_all_external = True
    return self

  def allow_external(self, key):
    self._allow_external.add(safe_name(key).lower())
    return self

  def allow_unverified(self, key):
    self._allow_unverified.add(safe_name(key).lower())
    return self

  def use_wheel(self):
    if WheelPackage not in self._precedence:
      self._precedence = (WheelPackage,) + self._precedence
    return self

  def no_use_wheel(self):
    self._precedence = tuple(
        [precedent for precedent in self._precedence if precedent is not WheelPackage])
    return self

  def allow_builds(self):
    if SourcePackage not in self._precedence:
      self._precedence = self._precedence + (SourcePackage,)
    return self

  def no_allow_builds(self):
    self._precedence = tuple(
        [precedent for precedent in self._precedence if precedent is not SourcePackage])
    return self

  def build(self, key):
    return ResolverOptions(
        fetchers=self._fetchers,
        allow_external=self._allow_all_external or key in self._allow_external,
        allow_unverified=key in self._allow_unverified,
        precedence=self._precedence,
        context=self._context,
    )


class ResolverOptions(ResolverOptionsInterface):
  def __init__(self,
               fetchers=None,
               allow_external=False,
               allow_unverified=False,
               precedence=None,
               context=None):
    self._fetchers = fetchers if fetchers is not None else [PyPIFetcher()]
    self._allow_external = allow_external
    self._allow_unverified = allow_unverified
    self._precedence = precedence if precedence is not None else Sorter.DEFAULT_PACKAGE_PRECEDENCE
    self._context = context or Context.get()

  # TODO(wickman) Revisit with Github #58
  def get_context(self):
    return self._context

  def get_crawler(self):
    return Crawler(self.get_context())

  # get_sorter and get_translator are arguably options that should be global
  # except that --no-use-wheel fucks this shit up.  hm.
  def get_sorter(self):
    return Sorter(self._precedence)

  def get_translator(self, interpreter, platform):
    translators = []

    # TODO(wickman) This is not ideal -- consider an explicit link between a Package
    # and its Installer type rather than mapping this here, precluding the ability to
    # easily add new package types (or we just forego that forever.)
    for package in self._precedence:
      if package is WheelPackage:
        translators.append(WheelTranslator(interpreter=interpreter, platform=platform))
      elif package is EggPackage:
        translators.append(EggTranslator(interpreter=interpreter, platform=platform))
      elif package is SourcePackage:
        installer_impl = WheelInstaller if WheelPackage in self._precedence else EggInstaller
        translators.append(SourceTranslator(installer_impl=installer_impl, interpreter=interpreter))

    return ChainedTranslator(*translators)

  def get_iterator(self):
    return Iterator(
        fetchers=self._fetchers,
        crawler=self.get_crawler(),
        follow_links=self._allow_external,
    )
