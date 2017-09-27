import include_def
import repository

import unittest
import ast


class IncludeDefTest(unittest.TestCase):
    def test_can_get_location_from_ast_call(self):
        include = self._parse_include_def('include_defs("//foo/DEFS")')
        self.assertEqual('//foo/DEFS', include.get_location())

    def test_can_get_include_path(self):
        repo = repository.Repository({'cell': '/repo/cell'})
        include = self._parse_include_def('include_defs("cell//pkg/DEFS")')
        self.assertEqual('/repo/cell/pkg/DEFS', include.get_include_path(repo))

    def _parse_include_def(self, code) -> include_def.IncludeDef:
        return include_def.from_ast_call(ast.parse(code).body[0].value)
