import unittest

from diff_rulekeys import *


class TestRuleKeyDiff(unittest.TestCase):
    def test_key_value_diff(self):
        list_diff = KeyValueDiff()
        list_diff.append('l', 'r')
        self.assertEqual(list_diff.diff(), ['-[l]', '+[r]'])

    def test_key_value_diff_sort(self):
        list_diff = KeyValueDiff()
        list_diff.append('1', '2')
        list_diff.append('2', '1')
        self.assertEqual(
            list_diff.diff(),
            ["Only order of entries differs: [1, 2] vs [2, 1]."])

    def test_key_value_diff_case(self):
        list_diff = KeyValueDiff()
        list_diff.append('B', 'a')
        list_diff.append('a', 'b')
        self.assertEqual(
            list_diff.diff(),
            ["Only order and letter casing (Upper Case vs lower case) of " +
             "entries differs:", '-[B]', '+[b]'])

if __name__ == '__main__':
    unittest.main()
