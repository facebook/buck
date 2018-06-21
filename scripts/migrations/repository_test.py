import unittest

import repository


class RepositoryTest(unittest.TestCase):
    def test_can_get_path_to_cell(self):
        repo = repository.Repository("/root", {"cell": "/cell"})
        self.assertEqual("/cell", repo.get_cell_path("cell"))

    def test_can_get_path_to_default_cell(self):
        repo = repository.Repository("/root", {"cell": "/cell"})
        self.assertEqual("/root", repo.get_cell_path(""))
