import unittest

from util import Util


class UtilTest(unittest.TestCase):
    def test_output(self):
        self.assertEqual("Python Generated CPP", Util().name)
