import unittest
from contextlib import contextmanager
from StringIO import StringIO

from rulekey_diff2 import *


@contextmanager
def captured_output():
    new_out, new_err = StringIO(), StringIO()
    old_out, old_err = sys.stdout, sys.stderr
    try:
        sys.stdout, sys.stderr = new_out, new_err
        yield sys.stdout, sys.stderr
    finally:
        sys.stdout, sys.stderr = old_out, old_err


class MockFile(object):
    def __init__(self, lines):
        self._index = -1
        self._lines = lines

    def readline(self):
        self._index += 1
        return self._lines[self._index]

    def readlines(self):
        return self._lines

    def __iter__(self):
        return iter(self._lines)


class TestRuleKeyDiff2(unittest.TestCase):
    def test_gather(self):
        self.assertEquals(
            gather(range(10), lambda x: x % 3),
            {0: [0, 3, 6, 9], 1: [1, 4, 7], 2: [2, 5, 8]},
        )

    def test_ordinals(self):
        self.assertEquals(ordinals([2, 3, 5, 7, 11]), {2: 0, 3: 1, 5: 2, 7: 3, 11: 4})

    def test_tokenize_rulekey_line(self):
        self.assertEquals(
            tokenize_rulekey_line('type1(val1a:val1b):type2(val2):type3("abc:def"):'),
            ['type3("abc:def")', "type2(val2)", "type1(val1a:val1b)"],
        )

    def test_read_rulekeys_from_diag_file(self):
        file1 = MockFile(
            [
                "rulekey1 type1(val1a:val1b):type2(val2):",
                "rulekey2 type3(val3):type4(val4):type5(val5):",
            ]
        )
        self.assertDictEqual(
            read_rulekeys(file1, "", " "),
            {
                "rulekey1": ["type2(val2)", "type1(val1a:val1b)"],
                "rulekey2": ["type5(val5)", "type4(val4)", "type3(val3)"],
            },
        )

    def test_read_rulekeys_from_bucklog_file(self):
        file1 = MockFile(
            [
                "[12:34:56][blah blah ...][com.facebook.buck.rules.keys.RuleKeyBuilder] "
                + "RuleKey rulekey1=type1(val1a:val1b):type2(val2):",
                "[12:37:11][blah blah ...][com.facebook.buck.rules.keys.RuleKeyFactory] "
                + "I am a rulekey factory! Blah! Blah! Ignore me!",
                "[13:41:08][blah blah ...][com.facebook.buck.rules.keys.RuleKeyBuilder] "
                + "RuleKey rulekey2=type3(val3):type4(val4):type5(val5):",
                "[12:37:11][blah blah ...][com.facebook.buck.parser.Parser] "
                + "I am a parser! Blah! Blah! Ignore me!",
            ]
        )
        self.assertDictEqual(
            read_rulekeys(
                file1, "[com.facebook.buck.rules.keys.RuleKeyBuilder] RuleKey ", "="
            ),
            {
                "rulekey1": ["type2(val2)", "type1(val1a:val1b)"],
                "rulekey2": ["type5(val5)", "type4(val4)", "type3(val3)"],
            },
        )

    def test_token_type(self):
        self.assertEquals(token_type("key(field1)"), "key")
        self.assertEquals(token_type("wrapper(OPTIONAL)"), "wrapper")
        self.assertEquals(token_type("container(LIST,len=5)"), "container")
        self.assertEquals(token_type("container(TUPLE,len=2)"), "container")
        self.assertEquals(token_type("number(42)"), "number")
        self.assertEquals(token_type('string("ab(c)")'), "string")

    def test_token_value(self):
        self.assertEquals(token_value("key(field1)"), "field1")
        self.assertEquals(token_value("wrapper(OPTIONAL)"), "OPTIONAL")
        self.assertEquals(token_value("container(LIST,len=5)"), "LIST,len=5")
        self.assertEquals(token_value("container(TUPLE,len=2)"), "TUPLE,len=2")
        self.assertEquals(token_value("number(42)"), "42")
        self.assertEquals(token_value('string("ab(c)")'), '"ab(c)"')

    def test_token_length(self):
        self.assertEquals(token_length("key(field1)"), 1)
        self.assertEquals(token_length("wrapper(OPTIONAL)"), 1)
        self.assertEquals(token_length("container(LIST,len=5)"), 5)
        self.assertEquals(token_length("container(TUPLE,len=2)"), 2)
        self.assertEquals(token_length("number(42)"), 0)
        self.assertEquals(token_length('string("ab(c)")'), 0)

    def test_print_rulekey(self):
        with captured_output() as (out, err):
            print_rulekey(
                [
                    "key(field1)",
                    "container(TUPLE,len=2)",
                    "container(LIST,len=3)",
                    'string("s1")',
                    'string("s2")',
                    'string("s3")',
                    "wrapper(OPTIONAL)",
                    'string("s4")',
                    "key(field2)",
                    "number(42)",
                ]
            )
        self.assertEquals(
            "\n".join(
                [
                    "key(field1)",
                    "  container(TUPLE,len=2)",
                    "    container(LIST,len=3)",
                    '      string("s1")',
                    '      string("s2")',
                    '      string("s3")',
                    "    wrapper(OPTIONAL)",
                    '      string("s4")',
                    "key(field2)",
                    "  number(42)",
                    "",
                ]
            ),
            out.getvalue(),
        )

    def test_reconstruct_rulekey(self):
        s = reconstruct_rulekey(
            [
                "key(field1)",
                "container(TUPLE,len=2)",
                "container(LIST,len=3)",
                'string("s1")',
                'string("s2")',
                'string("s3")',
                "wrapper(OPTIONAL)",
                'string("s4")',
                "key(field2)",
                "number(42)",
            ]
        )
        self.assertEquals(s.token, "root()")
        self.assertEquals(len(s), 2)
        self.assertEquals(s[0].token, "key(field1)")
        self.assertEquals(len(s[0]), 1)
        self.assertEquals(s[0][0].token, "container(TUPLE,len=2)")
        self.assertEquals(len(s[0][0]), 2)
        self.assertEquals(s[0][0][0].token, "container(LIST,len=3)")
        self.assertEquals(len(s[0][0][0]), 3)
        self.assertEquals(s[0][0][0][0].token, 'string("s1")')
        self.assertEquals(len(s[0][0][0][0]), 0)
        self.assertEquals(s[0][0][0][1].token, 'string("s2")')
        self.assertEquals(len(s[0][0][0][1]), 0)
        self.assertEquals(s[0][0][0][2].token, 'string("s3")')
        self.assertEquals(len(s[0][0][0][2]), 0)
        self.assertEquals(s[0][0][1].token, "wrapper(OPTIONAL)")
        self.assertEquals(len(s[0][0][1]), 1)
        self.assertEquals(s[0][0][1][0].token, 'string("s4")')
        self.assertEquals(len(s[0][0][1][0]), 0)
        self.assertEquals(s[1].token, "key(field2)")
        self.assertEquals(len(s[1]), 1)
        self.assertEquals(s[1][0].token, "number(42)")
        self.assertEquals(len(s[1][0]), 0)

    @staticmethod
    def diff_rulekeys_result(s1, s2):
        res = []

        def visitor(p1, _s1, p2, _s2):
            res.append((p1, p2))

        diff_rulekeys(s1, s2, visitor)
        return res

    def test_diff_rulekeys_insert_or_remove_element(self):
        s1 = reconstruct_rulekey(
            ["key(k1)", "container(LIST,len=2)", 'string("s1")', 'string("s3")']
        )
        s2 = reconstruct_rulekey(
            [
                "key(k1)",
                "container(LIST,len=3)",
                'string("s1")',
                'string("s2")',
                'string("s3")',
            ]
        )
        self.assertEquals(
            self.diff_rulekeys_result(s1, s2),
            [
                # report different length
                (
                    "/root():0/key(k1):0/container(LIST,len=2)",
                    "/root():0/key(k1):0/container(LIST,len=3)",
                ),
                # report 'None' on the left != 'string("s2")' on the right
                (
                    "/root():0/key(k1):0/container(LIST,len=2):None",
                    '/root():0/key(k1):0/container(LIST,len=3):1/string("s2")',
                ),
            ],
        )

    def test_diff_rulekeys_change_element_order(self):
        s1 = reconstruct_rulekey(
            [
                "key(k1)",
                "container(LIST,len=3)",
                'string("s1")',
                'string("s2")',
                'string("s3")',
            ]
        )
        s2 = reconstruct_rulekey(
            [
                "key(k1)",
                "container(LIST,len=3)",
                'string("s2")',
                'string("s3")',
                'string("s1")',
            ]
        )
        self.assertEquals(
            self.diff_rulekeys_result(s1, s2),
            [
                # report different order
                (
                    "/root():0/key(k1):0/container(LIST,len=3):order[0, 1, 2]",
                    "/root():0/key(k1):0/container(LIST,len=3):order[2, 0, 1]",
                )
            ],
        )

    def test_diff_rulekeys_insert_or_remove_key(self):
        s1 = reconstruct_rulekey(["key(k1)", 'string("s1")', "key(k3)", 'string("s3")'])
        s2 = reconstruct_rulekey(
            [
                "key(k1)",
                'string("s1")',
                "key(k2)",
                'string("s2")',
                "key(k3)",
                'string("s3")',
            ]
        )
        self.assertEquals(
            self.diff_rulekeys_result(s1, s2),
            [
                # report 'None' on the left != 'key(k2)' on the right
                ("/root():None", "/root():1/key(k2)")
            ],
        )

    def test_diff_rulekeys_change_key_order(self):
        s1 = reconstruct_rulekey(
            [
                "key(k1)",
                'string("s1")',
                "key(k2)",
                'string("s2")',
                "key(k3)",
                'string("s3")',
            ]
        )
        s2 = reconstruct_rulekey(
            [
                "key(k2)",
                'string("s2")',
                "key(k3)",
                'string("s3")',
                "key(k1)",
                'string("s1")',
            ]
        )
        self.assertEquals(
            self.diff_rulekeys_result(s1, s2),
            [
                # report different order
                ("/root():order[0, 1, 2]", "/root():order[2, 0, 1]")
            ],
        )

    def test_find_children(self):
        s = reconstruct_rulekey(
            [
                "key(field1)",
                "container(TUPLE,len=2)",
                "container(LIST,len=3)",
                'string("s1")',
                'string("s2")',
                'string("s3")',
                "wrapper(OPTIONAL)",
                'string("s4")',
                "key(field2)",
                "number(42)",
            ]
        )
        self.assertEquals(find_children(s, r"field1", 0), [])
        self.assertEquals(find_children(s, r"field1", 1), [s[0]])
        self.assertEquals(find_children(s, r"field2", 1), [s[1]])
        t = s[0][0]  # 'container(TUPLE,len=2)'
        self.assertEquals(
            find_children(s, r"string"), [t[0][0], t[0][1], t[0][2], t[1][0]]
        )

    def get_keys1(self):
        return {
            "rulekey1": [
                "key(deps)",
                "container(LIST,len=2)",
                'string("//fake:ruleB")',
                'string("//fake:ruleC")',
                "key(.rule_key_type)",
                'string("default")',
                "key(.target_name)",
                'string("//fake:ruleA")',
            ],
            "rulekey2": [
                "key(.rule_key_type)",
                'string("input")',
                "key(.target_name)",
                'string("//fake:ruleA")',
            ],
            "rulekey3": [
                "key(.rule_key_type)",
                'string("default")',
                "key(.target_name)",
                'string("//fake:ruleB")',
            ],
        }

    def test_find_keys(self):
        keys = self.get_keys1()
        self.assertEquals(sorted(find_keys(keys, [])), [])
        self.assertEquals(
            sorted(find_keys(keys, [(r"fake:ruleA", None)])), ["rulekey1", "rulekey2"]
        )
        self.assertEquals(
            sorted(
                find_keys(
                    keys,
                    [(r"fake:ruleA", ".target_name"), (r"default", ".rule_key_type")],
                )
            ),
            ["rulekey1"],
        )
        self.assertEquals(
            sorted(find_keys(keys, [(r"fake:ruleB", None)])), ["rulekey1", "rulekey3"]
        )
        self.assertEquals(
            sorted(find_keys(keys, [(r"fake:ruleB", ".target_name")])), ["rulekey3"]
        )

    def test_extract_target(self):
        tokens = [
            "key(.rule_key_type)",
            'string("default")',
            "key(.target_name)",
            'string("//fake:ruleB")',
        ]
        self.assertEquals(extract_target(reconstruct_rulekey(tokens)), "//fake:ruleB")

    def test_build_targets_to_rulekeys_index(self):
        keys = self.get_keys1()
        self.assertDictEqual(
            build_targets_to_rulekeys_index(keys),
            {"//fake:ruleA": ["rulekey1", "rulekey2"], "//fake:ruleB": ["rulekey3"]},
        )


if __name__ == "__main__":
    unittest.main()
