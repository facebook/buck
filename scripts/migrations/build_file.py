import ast
import logging
from typing import Dict, List

import include_def
import repository


class GlobalUsageVisitor(ast.NodeVisitor):
    """
    Visitor that records all global symbol definitions - functions,
    classes and variables.

    In order to do this, when AST is traversed, as soon as any definition
    that creates a nested context (e.g. FunctionDef) is encountered, its
    name is recorded and its children are not explored.
    """

    def __init__(self):
        self.globals = []

    def visit_FunctionDef(self, node):
        # visit only a global function definition
        # everything else is not in global context
        self.globals.append(node.name)

    def visit_ClassDef(self, node):
        # visit only a global class definition
        # everything else is not in global context
        self.globals.append(node.name)

    def visit_Lambda(self, node):
        pass  # nothing to see here

    def visit_Name(self, node):
        if isinstance(node.ctx, ast.Store):
            self.globals.append(node.id)


class BuildFile:
    """Represents build definition or extension file."""

    def __init__(self, ast_module: ast.Module) -> None:
        self.ast_module = ast_module

    def find_all_include_defs(self) -> List[include_def.IncludeDef]:
        """
        :return: all include_defs function calls.
        For example, for "include_defs("//foo/DEFS")" it will return [
            IncludeDef(
                Call(
                    func=Name(id='include_defs', ctx=Load()),
                    args=[Str(s='//foo/DEFS')], keywords=[])
                )
            )
        ]
        """
        return [
            include_def.from_ast_call(node)
            for node in ast.walk(self.ast_module)
            if isinstance(node, ast.Call)
            if isinstance(node.func, ast.Name)
            if node.func.id == "include_defs"
        ]

    def find_exported_symbols(self) -> List[str]:
        """
        :return: the set of symbols exported in the module (variables, function
        and class names).
        For example, for
        foo = 'Foo'
        def func():
           ...
        class Clazz:
           ...
        it would return ['foo', 'func', 'Clazz'].
        """
        global_usage_collector = GlobalUsageVisitor()
        global_usage_collector.visit(self.ast_module)
        return global_usage_collector.globals

    def get_exported_symbols_transitive_closure(
        self, repo: repository.Repository
    ) -> List[str]:
        """
        :return: the set of symbols exported in the module (variables, function
        and class names) and from files it imports and transitive closure of
        their imports.

        So for a build file with following content:
        include_defs("//DEFS")
        foo = "foo"
        and DEFS file with:
        bar = "bar"
        This method will return ["foo", "bar"]
        """
        includes = [include for include in self.find_all_include_defs()]
        transitive_closure = []  # type: List[str]
        for include in includes:
            include_file = from_path(include.get_include_path(repo))
            transitive_closure.extend(
                include_file.get_exported_symbols_transitive_closure(repo)
            )
        return self.find_exported_symbols() + transitive_closure

    def get_export_map(self, repo: repository.Repository) -> Dict[str, List[str]]:
        """
        Returns a map where keys are include_defs labels and values are lists
        of exported symbols in corresponding include files. For example, for a
        build file with
            include_defs("//DEFS")
        and DEFS file with
            foo = 'FOO'
        this method returns {'//DEFS': ['foo']}
        :param repo: The repository.
        """
        includes = [include for include in self.find_all_include_defs()]
        export_map = {}
        for include in includes:
            include_file = from_path(include.get_include_path(repo))
            export_map[
                include.get_label().to_import_string()
            ] = include_file.find_exported_symbols()
            export_map.update(include_file.get_export_map(repo))
        return export_map

    def _find_used_symbols(self) -> List[ast.Name]:
        """
        :return: the set of symbols used in the module (variables and function
        names). For example, for
            foo = 'Foo'
            func()
        it will return list of nodes with variable foo and function call func.
        """
        return [
            node
            for node in ast.walk(self.ast_module)
            if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load)
        ]

    def _find_all_function_calls_by_name(self, name: str) -> List[ast.Call]:
        """
        :param name: name of the function for which all calls should be
            returned.
        :return: a list of function calls for function with given name.
            For example, for
                foo('bar')
                bar('foo')
                foo('baz')
            _find_all_function_calls_by_name(m, 'foo') will return [
                Call(
                    func=Name(id='foo', ctx=Load()),
                    args=[Str(s='bar')],
                    keywords=[])),
                Call(
                    func=Name(id='foo', ctx=Load()),
                    args=[Str(s='baz')],
                    keywords=[]))
            ]
        """
        return [
            node
            for node in ast.walk(self.ast_module)
            if isinstance(node, ast.Call) and node.func.id == name
        ]


def from_content(content: str):
    return BuildFile(ast.parse(content))


def from_path(path: str):
    logging.debug("Creating build file from " + path)
    with open(path, "r") as f:
        return from_content(f.read())
