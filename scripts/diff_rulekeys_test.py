import unittest

from diff_rulekeys import *


class MockFile(object):
    def __init__(self, lines):
        self._lines = lines

    def readlines(self):
        return self._lines


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

    def test_key_value_diff_paths(self):
        list_diff = KeyValueDiff()
        list_diff.append('path(a.java:123)', 'path(a.java:322)')
        list_diff.append('path(C.java:123)', 'path(c.java:123)')
        list_diff.diff()
        self.assertEqual(
                set(list_diff.getInterestingPaths()),
                set(['a.java', 'c.java', 'C.java']))

    def test_structure_info(self):
        line = ("[v] RuleKey 00aa=string(\"//:rule\"):key(name):" +
                "number(1):key(version):string(\"Rule\"):key(buck.type):")
        info = RuleKeyStructureInfo(MockFile([line]))
        self.assertEqual(info.getNameForKey("00aa"), "//:rule")

    def test_simple_diff(self):
        line = ("[v] RuleKey {key}=string(\"//:lib\"):key(name):" +
                "path(JavaLib1.java:{hash}):key(srcs):" +
                "string(\"t\"):key(buck.type):")
        left_line = line.format(key="aabb", hash="aabb")
        right_line = line.format(key="cabb", hash="cabb")
        result = diff("//:lib",
                      RuleKeyStructureInfo(MockFile([left_line])),
                      RuleKeyStructureInfo(MockFile([right_line])),
                      False)
        expected = [
                'Change details for [//:lib]',
                '  (srcs):',
                '    -[path(JavaLib1.java:aabb)]',
                '    +[path(JavaLib1.java:cabb)]']
        self.assertEqual(result, expected)

    def test_simple_diff_with_custom_names(self):
        line = ("[v] RuleKey {key}=string(\"//:lib\"):key(name):" +
                "path(JavaLib1.java:{hash}):key(srcs):" +
                "string(\"t\"):key(buck.type):")
        left_line = line.format(key="aabb", hash="ll")
        right_line = line.format(key="aabb", hash="rr")
        result = diff("//:lib",
                      RuleKeyStructureInfo(MockFile([left_line])),
                      RuleKeyStructureInfo(MockFile([right_line])),
                      False,
                      format_tuple=('l(%s)', 'r{%s}'),
                      check_paths=True)
        expected = [
                'Change details for [//:lib]',
                '  (srcs):',
                '    l(path(JavaLib1.java:ll))',
                '    r{path(JavaLib1.java:rr)}',
                'Information on paths the script has seen:',
                ' JavaLib1.java does not exist']
        self.assertEqual(result, expected)

if __name__ == '__main__':
    unittest.main()
