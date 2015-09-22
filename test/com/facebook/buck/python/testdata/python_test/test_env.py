import os
import unittest


class Test(unittest.TestCase):

    def test_environment(self):
        self.assertEqual(os.environ['PYTHON_TEST_ENV_VAR'], '42')
