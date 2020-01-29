from __future__ import absolute_import, division, print_function, unicode_literals

import os
import unittest


class Test(unittest.TestCase):
    def test_query_targets(self):
        self.assertEqual(
            os.environ["TTAARRGGEETTSS"], "//query_targets:bar //query_targets:foo"
        )
