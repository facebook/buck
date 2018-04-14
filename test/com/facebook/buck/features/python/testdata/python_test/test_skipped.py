import unittest


class Test(unittest.TestCase):

    @unittest.skip("skipped!")
    def test_that_gets_skipped(self):
        self.fail('failure')
