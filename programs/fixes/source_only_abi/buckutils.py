from __future__ import absolute_import, division, print_function, unicode_literals

import ast
import os
import os.path
import re
from UserDict import DictMixin
from UserList import UserList

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
        print(
            "Manual step: {}: Delete at line {} col {}, insert {}".format(
                self.path, ast_node.lineno, ast_node.col_offset, value
            )
        )

    def prepend(self, ast_node, key, value=None):
        """Prepends a keyword argument to the list for a given call or list node. There must not be
        any unnamed arguments on the node."""
        assert isinstance(ast_node, ast.Call) or isinstance(ast_node, ast.List)

        if isinstance(ast_node, ast.Call):
            ast_node = ast_node.func
            open_char = "("
        else:
            open_char = "["

        for lineno in xrange(ast_node.lineno, len(self.lines) + 1):
            line = self.lines[lineno - 1]
            for col_offset in xrange(
                ast_node.col_offset if lineno == ast_node.lineno else 0, len(line)
            ):
                character = line[col_offset]
                if character == "#":
                    break
                elif character == open_char:
                    if value:
                        self.lines[lineno - 1] = (
                            line[: col_offset + 1]
                            + key
                            + " = "
                            + str(value)
                            + ", "
                            + line[col_offset + 1 :]
                        )
                    else:
                        self.lines[lineno - 1] = (
                            line[: col_offset + 1] + key + ", " + line[col_offset + 1 :]
                        )
                    return

    def write(self):
        """Writes back to the file all modifications accumulated in this object by calls to its
        other methods"""
        for target in self.targets.itervalues():
            target.save()

        with open(self.path, "w+") as buck_file:
            buck_file.writelines(self.lines)


class BuildTarget(DictMixin):
    """Provides basic utilities for reading and editing a single rule within a BUCK file"""

    def __init__(self, buck_file, ast_node):
        self.__buck_file = buck_file
        self.__rule_node = ast_node

        self.__args = {}
        self.__newargs = {}
        for keyword in self.__rule_node.keywords:
            if isinstance(keyword.value, ast.List):
                self.__args[keyword.arg] = EditableList(keyword.value)
            else:
                self.__args[keyword.arg] = keyword.value

    def keys(self):
        return list(set(self.__args.keys() + self.__newargs.keys()))

    def __getitem__(self, key):
        if key in self.__newargs:
            return self.__newargs[key]

        return self.__args[key]

    def __setitem__(self, key, value):
        if not hasattr(self.__args, key) or self.__args[key] != value:
            self.__newargs[key] = value

    def save(self):
        for key, value in self.__args.items():
            if isinstance(value, EditableList):
                value.save(self.__buck_file)

        for key, value in self.__newargs.items():
            if key in self.__args:
                self.__buck_file.replace(self.__args[key].value, value)
            else:
                self.__buck_file.prepend(self.__rule_node, key, value)


class EditableList:
    def __init__(self, ast_list=None):
        assert ast_list is None or isinstance(ast_list, ast.List)
        self.__ast_list = ast_list
        self.__new_items = []

    def __getitem__(self, item):
        return _ast_to_python(self.__buck_target[self.__attr][item])

    def __contains__(self, desired):
        if self.__ast_list:
            for item in self.__ast_list.elts:
                if _ast_to_python(item) == desired:
                    return True

        return desired in self.__new_items

    def prepend(self, item):
        self.__new_items.insert(0, item)

    def save(self, buck_file):
        for item in self.__new_items:
            buck_file.prepend(self.__ast_list, '"' + item + '"')

    def __str__(self):
        assert self.__ast_list is None
        return '["' + '", "'.join(self.__new_items) + '"]'


def _ast_to_python(ast_item):
    if isinstance(ast_item, ast.Str):
        return ast_item.s

    raise NotImplementedError(ast_item)


def _get_buck_root():
    return os.getcwd()  # TODO(jkeljo): Look for .buckconfig
