from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import unicode_literals

import ast
import os
import os.path
import re

BUILD_TARGET_PATTERN = re.compile(r"^(?P<cell>.*)?//(?P<path>[^:]+)?:(?P<name>.*)$")

__buck_files = {}


def get_buck_file(path):
    if path not in __buck_files:
        __buck_files[path] = BuckFile(path)

    return __buck_files[path]


def get_build_target(rule):
    rule = BUILD_TARGET_PATTERN.match(rule).groupdict()
    assert not hasattr(rule, "cell")  # TODO(jkeljo): Support cross-cell

    buck_file_name = os.path.join(_get_buck_root(), rule["path"], "BUCK")
    return get_buck_file(buck_file_name).targets[rule["name"]]


def write_all():
    for buck_file in __buck_files.itervalues():
        buck_file.write()


class BuckFile:
    """Provides basic utilities for making edits to a BUCK file"""

    def __init__(self, path):
        self.path = path
        with open(self.path) as buck_file:
            self.lines = buck_file.readlines()
        self.ast = ast.parse("".join(self.lines), self.path)
        self.targets = {}
        for statement in self.ast.body:
            if not isinstance(statement, ast.Expr):
                continue

            if not isinstance(statement.value, ast.Call):
                continue

            call = statement.value
            keywords = call.keywords

            for keyword in keywords:
                if keyword.arg == "name":
                    self.targets[keyword.value.s] = BuildTarget(self, call)

    def replace(self, ast_node, value):
        """Replaces the given AST node with the given string"""
        raise NotImplementedError(
            "{}: Delete at line {} col {}, insert {}".format(
                self.path,
                ast_node.lineno,
                ast_node.col_offset,
                value))

    def prepend(self, ast_node, key, value):
        """Prepends a keyword argument to the list for a given call node. There must not be any
        unnamed arguments on the node."""
        assert isinstance(ast_node, ast.Call)

        for lineno in xrange(ast_node.func.lineno, len(self.lines) + 1):
            line = self.lines[lineno - 1]
            for col_offset in xrange(
                    ast_node.func.col_offset if lineno == ast_node.func.lineno else 0,
                    len(line)):
                character = line[col_offset]
                if character == '#':
                    break
                elif character == '(':
                    self.lines[lineno - 1] = \
                        line[:col_offset + 1] + key + " = " + value + ", " + line[col_offset + 1:]
                    return

    def write(self):
        """Writes back to the file all modifications accumulated in this object by calls to its
        other methods"""
        for target in self.targets.itervalues():
            target.save()

        with open(self.path, "w+") as buck_file:
            buck_file.writelines(self.lines)


class BuildTarget:
    """Provides basic utilities for reading and editing a single rule within a BUCK file"""

    def __init__(self, buck_file, ast_node):
        self.__buck_file = buck_file
        self.__rule_node = ast_node

        self.__args = {}
        self.__newargs = {}
        for keyword in self.__rule_node.keywords:
            self.__args[keyword.arg] = keyword

    def __getitem__(self, key):
        if key in self.__newargs:
            return self.__newargs[key]
        return self.__args[key]

    def __setitem__(self, key, value):
        if not hasattr(self.__args, key) or self.__args[key] != value:
            self.__newargs[key] = value

    def save(self):
        for key, value in self.__newargs.items():
            if key in self.__args:
                self.__buck_file.replace(self.__args[key].value, value)
            else:
                self.__buck_file.prepend(self.__rule_node, key, value)


def _get_buck_root():
    return os.getcwd()  # TODO(jkeljo): Look for .buckconfig
