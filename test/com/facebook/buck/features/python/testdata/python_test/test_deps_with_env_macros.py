import os
import subprocess
import unittest


class Test(unittest.TestCase):
    def test_verify_environment(self):
        parts = os.environ.get("TESTING_BINARIES").split(" -- ")
        self.assertEquals(2, len(parts))
        self.assertEquals("file1.txt", os.path.basename(parts[0]))
        self.assertEquals("file2.txt", os.path.basename(parts[1]))
        print(parts)
        self.assertEquals("file1", open(parts[0], "r").read().strip())
        self.assertEquals("file2", open(parts[1], "r").read().strip())
