import unittest

from diff_rulekeys import *


class TestRuleKeyDiff(unittest.TestCase):
    def test_key_value_diff(self):
        list_diff = KeyValueDiff()
        list_diff.append('l', 'r')
        self.assertEqual(list_diff.diff(), ['-[l]', '+[r]'])

    def test_key_value_diff_with_common_elements(self):
        list_diff = KeyValueDiff()
        l = ['a', 'b', 'c']
        r = ['b', 'd', 'c']
        for l, r in map(None, l, r):
            list_diff.append(l, r)
        self.assertEqual(list_diff.diff(), ['-[a]', '+[d]'])

    def test_key_value_diff_with_common_elements_and_sort_issue(self):
        list_diff = KeyValueDiff()
        l = ['a', 'b', 'c']
        r = ['c', 'd', 'b']
        for l, r in map(None, l, r):
            list_diff.append(l, r)
        self.assertEqual(
                list_diff.diff(),
                ['-[a]',
                 '+[d]',
                 'Only order of remaining entries differs: [b, c] vs [c, b].'])

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
