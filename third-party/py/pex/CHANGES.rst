=======
CHANGES
=======

-----
1.1.1
-----

* Fix infinite recursion when ``PEX_PYTHON`` points at a symlink.
  `#182 <https://github.com/pantsbuild/pex/pull/182>`_

* Add ``/etc/pexrc`` to the list of pexrc locations to check.
  `#183 <https://github.com/pantsbuild/pex/pull/183>`_

* Improve error messaging for platform constrained Untranslateable errors.
  `#179 <https://github.com/pantsbuild/pex/pull/179>`_

-----
1.1.0
-----

* Add support for ``.pexrc`` files for influencing the pex environment. See the notes `here
  <https://github.com/pantsbuild/pex/blob/master/docs/buildingpex.rst#tailoring-pex-execution-at-build-time>`_.
  `#128 <https://github.com/pantsbuild/pex/pull/128>`_.

* Bug fix: PEX_PROFILE_FILENAME and PEX_PROFILE_SORT were not respected.
  `#154 <https://github.com/pantsbuild/pex/issues/154>`_.

* Adds the ``bdist_pex`` command to setuptools.
  `#99 <https://github.com/pantsbuild/pex/issues/99>`_.

* Bug fix: We did not normalize package names in ``ResolvableSet``, so it was possible to depend on
  ``sphinx`` and ``Sphinx-1.4a0.tar.gz`` and get two versions build and included into the pex.
  `#147 <https://github.com/pantsbuild/pex/issues/147>`_.

* Adds a pex-identifying User-Agent. `#101 <https://github.com/pantsbuild/pex/issues/101>`_.

-----
1.0.3
-----

* Bug fix: Accommodate OSX ``Python`` python binaries.  Previously the OSX python distributions shipped
  with OSX, XCode and available via https://www.python.org/downloads/ could fail to be detected using
  the ``PythonInterpreter`` class.
  Fixes `#144 <https://github.com/pantsbuild/pex/issues/144>`_.

* Bug fix: PEX_SCRIPT failed when the script was from a not-zip-safe egg.
  Original PR `#139 <https://github.com/pantsbuild/pex/pull/139>`_.

* Bug fix: ``sys.exit`` called without arguments would cause `None` to be printed on stderr since pex 1.0.1.
  `#143 <https://github.com/pantsbuild/pex/pull/143>`_.

-----
1.0.2
-----

* Bug fix: PEX-INFO values were overridden by environment ``Variables`` with default values that were
  not explicitly set in the environment.
  Fixes `#135 <https://github.com/pantsbuild/pex/issues/135>`_.

* Bug fix: Since `69649c1 <https://github.com/pantsbuild/pex/commit/69649c1>`_ we have been unpatching
  the side-effects of ``sys.modules`` after ``PEX.execute``.  This takes all modules imported during
  the PEX lifecycle and sets all their attributes to ``None``.  Unfortunately, ``sys.excepthook``,
  ``atexit`` and ``__del__`` may still try to operate using these tainted modules, causing exceptions
  on interpreter teardown.  This reverts just the ``sys`` unpatching so that the abovementioned
  teardown hooks behave more predictably.
  Fixes `#141 <https://github.com/pantsbuild/pex/issues/141>`_.

-----
1.0.1
-----

* Allow PEXBuilder to optionally copy files into the PEX environment instead of hard-linking them.

* Allow PEXBuilder to optionally skip precompilation of .py files into .pyc files.

* Bug fix: PEXBuilder did not respect the target interpreter when compiling source to bytecode.
  Fixes `#127 <https://github.com/pantsbuild/pex/issues/127>`_.

* Bug fix: Fix complex resolutions when using a cache.
  Fixes: `#120 <https://github.com/pantsbuild/pex/issues/120>`_.

-----
1.0.0
-----

The 1.0.0 release of pex introduces a few breaking changes: ``pex -r`` now takes requirements.txt files
instead of requirement specs, ``pex -s`` has now been removed since source specs are accepted as arguments,
and ``pex -p`` has been removed in favor of its alias ``pex -o``.

The pex *command line interface* now adheres to semver insofar as backwards incompatible CLI
changes will invoke a major version change.  Any backwards incompatible changes to the PEX
environment variable semantics will also result in a major version change.  The pex *API* adheres
to semver insofar as backwards incompatible API changes will invoke minor version changes.

For users of the PEX API, it is recommended to add minor version ranges, e.g. ``pex>=1.0,<1.1``.
For users of the PEX CLI, major version ranges such as ``pex>=1,<2`` should be sufficient.

* BREAKING CHANGE: Removes the ``-s`` option in favor of specifying directories directly as
  arguments to the pex command line.

* BREAKING CHANGE: ``pex -r`` now takes requirements.txt filenames and *not* requirement
  specs.  Requirement specs are now passed as arguments to the pex tool.  Use ``--`` to escape
  command line arguments passed to interpreters spawned by pex.  Implements
  `#5 <https://github.com/pantsbuild/pex/issues/5>`_.

* Adds a number of flag aliases to be more compatible with pip command lines: ``--no-index``,
  ``-f``, ``--find-links``, ``--index-url``, ``--no-use-wheel``.  Removes ``-p`` in favor of
  ``-o`` exclusively.

* Adds ``--python-shebang`` option to the pex tool in order to set the ``#!`` shebang to an exact
  path.  `#53 <https://github.com/pantsbuild/pex/issues/53>`_.

* Adds support for ``PEX_PYTHON`` environment variable which will cause the pex file to reinvoke
  itself using the interpreter specified, e.g. ``PEX_PYTHON=python3.4`` or
  ``PEX_PYTHON=/exact/path/to/interpreter``.  `#27 <https://github.com/pantsbuild/pex/issues/27>`_.

* Adds support for ``PEX_PATH`` environment variable which allows merging of PEX environments at
  runtime.  This can be used to inject plugins or entry_points or modules from one PEX into
  another without explicitly building them together. `#30 <https://github.com/pantsbuild/pex/issues/30>`_.

* Consolidates documentation of ``PEX_`` environment variables and adds the ``--help-variables`` option
  to the pex client.  Partially addresses `#13 <https://github.com/pantsbuild/pex/issues/13>`_.

* Adds helper method to dump a package subdirectory onto disk from within a zipped PEX file.  This
  can be useful for applications that know they're running within a PEX and would prefer some
  static assets dumped to disk instead of running as an unzipped PEX file.
  `#12 <https://github.com/pantsbuild/pex/pull/12>`_.

* Now supports extras for static URLs and installable directories.
  `#65 <https://github.com/pantsbuild/pex/issues/65>`_.

* Adds ``-m`` and ``--entry-point`` alias to the existing ``-e`` option for entry points in
  the pex tool to evoke the similarity to ``python -m``.

* Adds console script support via ``-c/--script/--console-script`` and ``PEX_SCRIPT``.  This allows
  you to reference the named entry point instead of the exact ``module:name`` pair.  Also supports
  scripts defined in the ``scripts`` section of setup.py.
  `#59 <https://github.com/pantsbuild/pex/issues/59>`_.

* Adds more debugging information when encountering unresolvable requirements.
  `#79 <https://github.com/pantsbuild/pex/issues/79>`_.

* Bug fix: ``PEX_COVERAGE`` and ``PEX_PROFILE`` did not function correctly when SystemExit was raised.
  Fixes `#81 <https://github.com/pantsbuild/pex/issues/81>`_.

* Bug fix: Fixes caching in the PEX tool since we don't cache the source distributions of installable
  directories.  `#24 <https://github.com/pantsbuild/pex/issues/24>`_.

-----
0.9.0
-----

This is the last release before the 1.0.0 development branch is started.

* Change the setuptools range to >=2.2,<16 by handling EntryPoint changes as well as
  being flexible on whether ``pkg_resources`` is a package or a module.  Fixes
  `#55 <https://github.com/pantsbuild/pex/issues/55>`_ and
  `#34 <https://github.com/pantsbuild/pex/issues/34>`_.

* Adds option groups to the pex tool to make the help output slightly more readable.

* Bug fix: Make ``pip install pex`` work better by removing ``extras_requires`` on the
  ``console_script`` entry point.  Fixes `#48 <https://github.com/pantsbuild/pex/issues/48>`_

* New feature: Adds an interpreter cache to the ``pex`` tool.  If the user does not explicitly
  disable the wheel feature and attempts to build a pex with wheels but does not have the wheel
  package installed, pex will download it in order to make the feature work.
  Implements `#47 <https://github.com/pantsbuild/pex/issues/47>`_ in order to
  fix `#48 <https://github.com/pantsbuild/pex/issues/48>`_

-----
0.8.6
-----

* Bug fix: Honor installed sys.excepthook in pex teardown.
  `RB #1733 <https://rbcommons.com/s/twitter/r/1733>`_

* Bug fix: ``UrllibContext`` used ``replace`` as a keyword argument for ``bytes.decode``
  but this only works on Python 3.  `Pull Request #46 <https://github.com/pantsbuild/pex/pull/46>`_

-----
0.8.5
-----

* Bug fix: Fixup string formatting in pex/bin/pex.py to support Python 2.6
  `Pull Request #40 <https://github.com/pantsbuild/pex/pull/40>`_

-----
0.8.4
-----

* Performance improvement: Speed up the best-case scenario of dependency resolution.
  `RB #1685 <https://rbcommons.com/s/twitter/r/1685>`_

* Bug fix: Change from ``uuid4().get_hex()`` to ``uuid4().hex`` to maintain Python3
  compatibility of pex.common.
  `Pull Request #39 <https://github.com/pantsbuild/pex/pull/39>`_

* Bug fix: Actually cache the results of translation.  Previously bdist translations
  would be created in a temporary directory even if a cache location was specified.
  `RB #1666 <https://rbcommons.com/s/twitter/r/1666>`_

* Bug fix: Support all potential abi tag permutations when determining platform
  compatibility.
  `Pull Request #33 <https://github.com/pantsbuild/pex/pull/33>`_

-----
0.8.3
-----

* Performance improvement: Don't always write packages to disk if they've already been
  cached.  This can significantly speed up launching PEX files with a large
  number of non-zip-safe dependencies.
  `RB #1642 <https://rbcommons.com/s/twitter/r/1642>`_

-----
0.8.2
-----

* Bug fix: Allow pex 0.8.x to parse pex files produced by earlier versions of
  pex and twitter.common.python.

* Pin pex to setuptools prior to 9.x until we have a chance to make changes
  related to PEP440 and the change of pkg_resources.py to a package.

-----
0.8.1
-----

* Bug fix: Fix issue where it'd be possible to ``os.path.getmtime`` on a remote ``Link`` object
  `Issue #29 <https://github.com/pantsbuild/pex/issues/29>`_

-----
0.8.0
-----

* *API change*: Decouple translation from package iteration.  This removes
  the Obtainer construct entirely, which likely means if you're using PEX as
  a library, you will need to change your code if you were doing anything
  nontrivial.  This adds a couple new options to ``resolve`` but simplifies
  the story around how to cache packages.
  `RB #785 <https://rbcommons.com/s/twitter/r/785/>`_

* Refactor http handling in pex to allow for alternate http implementations.  Adds support
  for `requests <https://github.com/kennethreitz/requests>`_,
  improving both performance and security.   For more information, read the commit notes at
  `91c7f32 <https://github.com/pantsbuild/pex/commit/91c7f324085c18af714d35947b603a5f60aeb682>`_.
  `RB #778 <https://rbcommons.com/s/twitter/r/778/>`_

* Improvements to API documentation throughout.

* Renamed ``Tracer`` to ``TraceLogger`` to prevent nondeterministic isort ordering.

* Refactor tox.ini to increase the number of environment combinations and improve coverage.

* Adds HTTP retry support for the RequestsContext.
  `RB #1303 <https://rbcommons.com/s/twitter/r/1303/>`_

* Make pex --version correct.
  `Issue #19 <https://github.com/pantsbuild/pex/issues/19>`_

* Bug fix: Fix over-aggressive sys.modules scrubbing for namespace packages.  Under
  certain circumstances, namespace packages in site-packages could conflict with packages
  within a PEX, causing them to fail importing.
  `RB #1378 <https://rbcommons.com/s/twitter/r/1378/>`_

* Bug fix: Replace uses of ``os.unsetenv(...)`` with ``del os.environ[...]``
  `Pull Request #11 <https://github.com/pantsbuild/pex/pull/11>`_

* Bug fix: Scrub sys.path and sys.modules based upon both supplied path and
  realpath of files and directories.  Newer versions of virtualenv on Linux symlink site-packages
  which caused those packages to not be removed from sys.path correctly.
  `Issue #21 <https://github.com/pantsbuild/pex/issues/21>`_

* Bug fix: The pex -s option was not correctly pulling in transitive dependencies.
  `Issue #22 <https://github.com/pantsbuild/pex/issues/22>`_

* Bug fix: Adds ``content`` method to HTTP contexts that does HTML content decoding, fixing
  an encoding issue only experienced when using Python 3.
  `Issue #10 <https://github.com/pantsbuild/pex/issues/10>`_

-----
0.7.0
-----

* Rename ``twitter.common.python`` to ``pex`` and split out from the
  `twitter/commons <http://github.com/twitter/commons>`_ repo.

-----
0.6.0
-----

* Change the interpretation of ``-i`` (and of PyPIFetcher's pypi_base)
  to match pip's ``-i``.  This is useful for compatibility with devpi.

------
0.5.10
------

* Ensures that .egg/.whl distributions on disk have their mtime updated
  even though we no longer overwrite them. This gives them a new time
  lease against their ttl.

  Without this change, once a distribution aged past the ttl it would
  never be used again, and builds would re-create the same distributions
  in tmpdirs over and over again.

-----
0.5.9
-----

* Fixes an issue where SourceTranslator would overwrite .egg/.whl
  distributions already on disk.  Instead it should always check to see if
  a copy already exists and reuse if there.

  This ordinarily should not be a problem but the zipimporter caches
  metadata by filename instead of stat/sha, so if the underlying contents
  changed a runtime error would be thrown due to seemingly corrupt zip file
  offsets. `RB #684 <https://rbcommons.com/s/twitter/r/684/>`_

-----
0.5.8
-----

* Adds ``-i/--index`` option to the pex tool.

-----
0.5.7
-----

* Adds ``twitter.common.python.pex_bootstrap`` ``bootstrap_pex_env`` function in
  order to initialize a PEX environment from within a python interpreter.
  (Patch contributed by @kwlzn)

* Adds stdin=,stdout=,stderr= keyword parameters to the ``PEX.run`` function.
  (Patch from @benjy)

-----
0.5.6
-----

* The crawler now defaults to not follow links for security reasons.
  (Before the default behavior was to implicitly ``--follow-links`` for all
  requirements.) `RB #293 <https://rbcommons.com/s/twitter/r/293/>`_

-----
0.5.5
-----

* Improves scrubbing of site-packages from PEX environments.
  `RB #289 <https://rbcommons.com/s/twitter/r/289/>`_

-------------
0.5.1 - 0.5.4
-------------

* Silences exceptions reported during interpreter teardown (the exceptions
  resulting from incorrect atexit handler behavior) introduced by 0.4.3
  `RB #253 <https://rbcommons.com/s/twitter/r/253/>`_
  `RB #249 <https://rbcommons.com/s/twitter/r/249/>`_

* Adds ``__hash__`` to ``Link`` so that Packages are hashed correctly in
  ``twitter.common.python.resolver`` ``resolve``

-----
0.5.0
-----

* Adds wheel support to ``twitter.common.python``
  `RB #94 <https://rbcommons.com/s/twitter/r/94/>`_
  `RB #154 <https://rbcommons.com/s/twitter/r/154/>`_
  `RB #148 <https://rbcommons.com/s/twitter/r/148/>`_

-----
0.4.3
-----

* Adds ``twitter.common.python.finders`` which are additional finders for
  setuptools including:
  - find eggs within a .zip
  - find wheels within a directory
  - find wheels within a .zip
  `RB #86 <https://rbcommons.com/s/twitter/r/86/>`_

* Adds a new Package abstraction by refactoring Link into Link and Package.
  `RB #92 <https://rbcommons.com/s/twitter/r/92/>`_

* Adds support for PEP425 tagging necessary for wheel support.
  `RB #87 <https://rbcommons.com/s/twitter/r/87/>`_

* Improves python environment isolation by correctly scrubbing namespace
  packages injected into module ``__path__`` attributes by nspkg pth files.
  `RB #116 <https://rbcommons.com/s/twitter/r/116/>`_

* Adds ``twitter.common.python.resolver`` ``resolve`` method that handles
  transitive dependency resolution better.  This means that if the
  requirement ``futures==2.1.2`` and an unqualified ``futures>=2`` is pulled in
  transitively, our resolver will correctly resolve futures 2.1.2 instead
  of reporting a VersionConflict if any version newer than 2.1.2 is
  available. `RB #129 <https://rbcommons.com/s/twitter/r/129/>`_

* Factors all ``twitter.common.python`` test helpers into
  ``twitter.common.python.testing``
  `RB #91 <https://rbcommons.com/s/twitter/r/91/>`_

* Bug fix: Fix ``OrderedSet`` atexit exceptions
  `RB #147 <https://rbcommons.com/s/twitter/r/147/>`_

* Bug fix: Fix cross-device symlinking (patch from @benjy)

* Bug fix: Raise a ``RuntimeError`` if we fail to write ``pkg_resources`` into a .pex
  `RB #115 <https://rbcommons.com/s/twitter/r/115/>`_

-----
0.4.2
-----

* Upgrade to ``setuptools>=1``

-----
0.4.1
-----

* ``twitter.common.python`` is no longer a namespace package

-----
0.4.0
-----

* Kill the egg distiller.  We now delegate .egg generation to bdist_egg.
  `RB #55 <https://rbcommons.com/s/twitter/r/55/>`_

-----
0.3.1
-----

* Short-circuit resolving a distribution if a local exact match is found.
  `RB #47 <https://rbcommons.com/s/twitter/r/47/>`_

* Correctly patch the global ``pkg_resources`` ``WorkingSet`` for the lifetime
  of the Python interpreter. `RB #52 <https://rbcommons.com/s/twitter/r/52/>`_

* Fixes a performance regression in setuptools ``build_zipmanifest``
  `Setuptools Issue #154 <https://bitbucket.org/pypa/setuptools/issue/154/build_zipmanifest-results-should-be>`_
  `RB #53 <https://rbcommons.com/s/twitter/r/53/>`_

-----
0.3.0
-----

* Plumb through the ``--zip-safe``, ``--always-write-cache``, ``--ignore-errors``
  and ``--inherit-path`` flags to the pex tool.

* Delete the unused ``PythonDirWrapper`` code.

* Split ``PEXEnvironment`` resolution into ``twitter.common.python.environment``
  and deconflate ``WorkingSet``/``Environment`` state.

* Removes the monkeypatched zipimporter in favor of keeping all eggs
  unzipped within PEX files.  Refactors the PEX dependency cache in
  ``util.py``

* Adds interpreter detection for Jython and PyPy.

* Dependency translation errors should be made uniform.
  (Patch from @johnsirois)

* Adds ``PEX_PROFILE_ENTRIES`` to limit the number of entries reported when
  ``PEX_PROFILE`` is enabled. (Patch from @rgs_)

* Bug fix: Several fixes to error handling in ``twitter.common.python.http``
  (From Marc Abramowitz)

* Bug fix: PEX should not always assume that ``$PATH`` was available.
  (Patch from @jamesbroadhead)

* Bug fix: Filename should be part of the .pex cache key or else multiple
  identical versions will incorrectly resolve (Patch from @tc)

* Bug fix: Executed entry points shouldn't be forced to run in an
  environment with ``__future__`` imports enabled. (Patch from
  @lawson_patrick)

* Bug fix: Detect versionless egg links and fail fast. (Patch from
  @johnsirois.)

* Bug fix: Handle setuptools>=2.1 correctly in the zipimport monkeypatch
  (Patch from @johnsirois.)

-----
0.2.3
-----

* Bug fix: Fix handling of Fetchers with ``file://`` urls.

-----
0.2.2
-----

* Adds the pex tool as a standalone tool.

-----
0.2.1
-----

* Bug fix: Bootstrapped ``twitter.common.python`` should declare ``twitter.common``
  as a namespace package.

-----
0.2.0
-----

* Make ``twitter.common.python`` fully standalone by consolidating
  external dependencies within ``twitter.common.python.common``.

-----
0.1.0
-----

* Initial published version of ``twitter.common.python``.
