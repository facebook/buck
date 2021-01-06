# Copyright (c) Facebook, Inc. and its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import argparse
import errno
import os
import py_compile
import sys


if sys.version_info[0] == 3:
    import importlib

    DEFAULT_FORMAT = importlib.util.cache_from_source("{pkg}/{name}.py")
else:
    DEFAULT_FORMAT = "{pkg}/{name}.pyc"


def get_py_path(module):
    return module.replace(".", os.sep) + ".py"


def get_pyc_path(module, fmt):
    try:
        package, name = module.rsplit(".", 1)
    except ValueError:
        package, name = "", module
    parts = fmt.split(os.sep)
    for idx in range(len(parts)):
        if parts[idx] == "{pkg}":
            parts[idx] = package.replace(".", os.sep)
        elif parts[idx].startswith("{name}"):
            parts[idx] = parts[idx].format(name=name)
    return os.path.join(*parts)


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("-o", "--output", required=True)
    parser.add_argument("-f", "--format", default=DEFAULT_FORMAT)
    parser.add_argument("-i", "--ignore-errors", action="store_true")
    parser.add_argument("modules", nargs="*")
    args = parser.parse_args(argv[1:])

    for module_and_src in args.modules:
        module, src = module_and_src.split("=", 1)
        pyc = os.path.join(args.output, get_pyc_path(module, args.format))
        try:
            os.makedirs(os.path.dirname(pyc))
        except OSError as e:
            if e.errno != errno.EEXIST:
                raise
        py_compile.compile(
            src,
            cfile=pyc,
            dfile=get_py_path(module),
            doraise=not args.ignore_errors,
            invalidation_mode=py_compile.PycInvalidationMode.UNCHECKED_HASH,
        )


sys.exit(main(sys.argv))
