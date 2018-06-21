import unittest


class Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raise Exception("setup failure!")

    def test_that_passes(self):
        pass
