import unittest


class Test(unittest.TestCase):

    def test_that_passes(self):
        pass

    def test_that_fails(self):
        self.fail('failure')
