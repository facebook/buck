# Copyright 2018-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

from __future__ import absolute_import, division, print_function, with_statement

import contextlib
import imp
import inspect

from six.moves import builtins
from six.moves.builtins import __import__ as ORIGINAL_IMPORT

from . import util


class ImportWhitelistManager(object):
    def __init__(self, import_whitelist, safe_modules_config, path_predicate):
        """
        :param (str)->bool path_predicate:
            Predicate returning whether the import whitelist applies to imports from a particular
            file.
        :param set[str] import_whitelist: modules which can be imported without restriction.
        :param dict[str,list[str]] safe_modules_config:
            Whitelisted parts for specified module.
            Supports submodules, e.g. for a safe version of module 'foo' with submodule 'bar'
            specify ``{'foo': ['bar', 'fun1', 'fun2'], 'foo.bar': ['fun3', 'fun4']}``.
        """
        self._import_whitelist = frozenset(import_whitelist)
        self._safe_modules_config = safe_modules_config
        self._path_predicate = path_predicate
        self._safe_modules = {}  #: cache of safe modules created via config.

    @contextlib.contextmanager
    def allow_unsafe_import(self, allow=True):
        """Controls behavior of 'import' in a context.

        :param bool allow: whether default 'import' behavior should be allowed in the context
        """

        # Override '__import__' function. It might have already been overridden if current file
        # was included by other build file, original '__import__' is stored in 'ORIGINAL_IMPORT'.
        previous_import = builtins.__import__
        if allow:
            builtins.__import__ = ORIGINAL_IMPORT
        else:
            builtins.__import__ = self._custom_import

        try:
            yield
        finally:
            # Restore previous 'builtins.__import__'
            builtins.__import__ = previous_import

    def _custom_import(self, name, globals=None, locals=None, fromlist=(), level=-1):
        """Custom '__import__' function.

        Returns safe version of a module if configured in `_safe_module_config`.
        Otherwise, returns standard module if the module is whitelisted.
        Otherwise, blocks importing other modules.
        """
        if not fromlist:
            # Return the top-level package if 'fromlist' is empty (e.g. 'os' for 'os.path'),
            # which is how '__import__' works.
            name = name.split(".")[0]

        frame = util.get_caller_frame(skip=[__name__])
        filename = inspect.getframeinfo(frame).filename

        # The import will be always allowed if it was not called from a project file.
        if name in self._import_whitelist or not self._path_predicate(filename):
            # Importing a module may cause more '__import__' calls if the module uses other
            # modules. Such calls should not be blocked if the top-level import was allowed.
            with self.allow_unsafe_import():
                return ORIGINAL_IMPORT(name, globals, locals, fromlist, level)

        # Return safe version of the module if possible
        if name in self._safe_modules_config:
            return self._get_safe_module(name)

        raise ImportError(
            "Importing module {0} is forbidden. "
            "If you really need to import this module, read about "
            "the allow_unsafe_import() function documented at: "
            "https://buckbuild.com/function/allow_unsafe_import.html".format(name)
        )

    @staticmethod
    def _block_unsafe_function(module, name):
        """Returns a function that ignores any arguments and raises AttributeError. """

        def func(*args, **kwargs):
            raise AttributeError(
                "Using function {0} is forbidden in the safe version of "
                "module {1}. If you really need to use this function read about "
                "allow_unsafe_import() documented at: "
                "https://buckbuild.com/function/allow_unsafe_import.html".format(
                    name, module
                )
            )

        return func

    def _install_whitelisted_parts(self, mod, safe_mod, whitelist):
        """Copy whitelisted globals from a module to its safe version.

        Functions not on the whitelist are blocked to show a more meaningful error.
        """

        mod_name = safe_mod.__name__
        whitelist_set = set(whitelist)
        for name in mod.__dict__:
            if name in whitelist_set:
                # Check if a safe version is defined in case it's a submodule.
                # If it's not defined the original submodule will be copied.
                submodule_name = mod_name + "." + name
                if submodule_name in self._safe_modules_config:
                    # Get a safe version of the submodule
                    safe_mod.__dict__[name] = self._get_safe_module(submodule_name)
                else:
                    safe_mod.__dict__[name] = mod.__dict__[name]
            elif callable(mod.__dict__[name]):
                safe_mod.__dict__[name] = self._block_unsafe_function(mod_name, name)

    def _get_safe_module(self, name):
        """Returns a safe version of the module."""

        assert name in self._safe_modules_config, (
            "Safe version of module %s is not configured." % name
        )

        # Return the safe version of the module if already created
        if name in self._safe_modules:
            return self._safe_modules[name]

        # Get the normal module, non-empty 'fromlist' prevents returning top-level package
        # (e.g. 'os' would be returned for 'os.path' without it)
        with self.allow_unsafe_import():
            mod = ORIGINAL_IMPORT(name, fromlist=[""])

        # Build a new module for the safe version
        safe_mod = imp.new_module(name)

        # Install whitelisted parts of the module, block the rest to produce errors
        # informing about the safe version.
        self._install_whitelisted_parts(mod, safe_mod, self._safe_modules_config[name])

        # Store the safe version of the module
        self._safe_modules[name] = safe_mod

        return safe_mod
