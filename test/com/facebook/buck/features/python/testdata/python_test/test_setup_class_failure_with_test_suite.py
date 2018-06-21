import unittest


class Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raise Exception("setup failure!")

    def test_that_passes(self):
        pass


def load_tests(loader, tests, pattern):
    suite = unittest.TestSuite()
    suite.addTests(loader.loadTestsFromTestCase(Test))
    return suite
