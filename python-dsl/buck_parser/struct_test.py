from __future__ import absolute_import
from __future__ import division
from __future__ import print_function
from __future__ import with_statement

from . import struct

import copy

import unittest


class StructTest(unittest.TestCase):
    def testPropertyAccess(self):
        self.assertEqual(struct.struct(foo="bar").foo, "bar")

    def testJsonSerialization(self):
        self.assertEqual(
            struct.struct(foo="bar").to_json(),
            "{\"foo\":\"bar\"}")

    def testNestedJsonSerialization(self):
        self.assertEqual(
            struct.struct(foo=struct.struct(bar="baz")).to_json(),
            "{\"foo\":{\"bar\":\"baz\"}}")

    def testCannotMutateAField(self):
        with self.assertRaisesRegexp(
                AttributeError,
                "Mutation of struct attributes \('foo'\) is not allowed."):
            struct.struct(foo="foo").foo = "bar"

    def testCanCopy(self):
        original = struct.struct(foo="bar")
        copied = copy.copy(original)
        self.assertEqual(original, copied)
        self.assertIsNot(original, copied)

    def testCanDeepCopy(self):
        original = struct.struct(foo="bar")
        deepcopied = copy.deepcopy(original)
        self.assertEqual(original, deepcopied)
        self.assertIsNot(original, deepcopied)

    def testInequality(self):
        x = struct.struct(foo="bar")
        y = struct.struct(foo="baz")
        self.assertNotEqual(x, y)
