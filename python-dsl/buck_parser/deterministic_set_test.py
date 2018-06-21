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
