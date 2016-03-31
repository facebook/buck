import unittest

import simple


class TestSimple(unittest.TestCase):
    def test_simple(self):
        self.assertEqual(1, simple.foo())
