# Copyright 2018-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

import random
import unittest

from .deterministic_set import DeterministicSet


class DeterministicSetTest(unittest.TestCase):
    def test_empty_depset_can_be_created(self):
        self.assertIsNotNone(DeterministicSet())

    def test_depset_iteration_order_is_sorted(self):
        # depset contract does not state that the order is sorted, but
        # currently the easiest way to implement determinism is to sort
        random_elements = [random.shuffle(range(99))]
        self.assertEqual(
            list(DeterministicSet(random_elements)), sorted(random_elements)
        )

    def test_depset_to_list_converts_it_to_list(self):
        self.assertEqual([1, 2, 3], DeterministicSet([1, 2, 3]).to_list())

    def test_plus_operator_can_union_depsets(self):
        self.assertEqual(
            DeterministicSet([1, 2]), DeterministicSet([1]) + DeterministicSet([2])
        )


if __name__ == "__main__":
    unittest.main()
