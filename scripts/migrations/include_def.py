import ast
import os

import label
import repository


class IncludeDef:
    """
    Represents build file include definition like
        include_defs("//include/path").
    """

    def __init__(self, ast_call: ast.Call) -> None:
        self.ast_call = ast_call

    def get_location(self) -> str:
        """
        Returns an include definition location.

        For include_defs("//include/path") it is "//include/path".
        """
        return self.ast_call.args[0].s

    def get_label(self) -> label.Label:
        """Returns a label identifying a build extension file."""
        return label.from_string(self.get_location())

    def get_include_path(self, repo: repository.Repository):
        """Returns a path to a file from which symbols should be imported."""
        l = self.get_label()
        return os.path.join(repo.get_cell_path(l.cell), l.package)


def from_ast_call(ast_call: ast.Call) -> IncludeDef:
    """
    IncludeDef factory method that creates instances from ast Call description.
    """
    return IncludeDef(ast_call)
