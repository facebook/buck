import os
import subprocess
import unittest


class Test(unittest.TestCase):
    def test_verify_environment(self):
        parts = os.environ.get("TESTING_BINARIES").split(" -- ")
        self.assertEquals(2, len(parts))
        self.assertEquals("sh_binary1.sh", os.path.basename(parts[0]))
        self.assertEquals("sh_binary2.sh", os.path.basename(parts[1]))
        print(parts)
        self.assertEquals("sh_binary1", subprocess.check_output(parts[0]).strip())
        self.assertEquals("sh_binary2", subprocess.check_output(parts[1]).strip())
