import os
import shutil
import subprocess
import sys
import tempfile
import unittest

from project_workspace import ProjectWorkspace


class ClassLoaderTest(unittest.TestCase):
    def test_should_not_pollute_classpath_when_processor_path_is_set(self):
        """
        Tests that annotation processors get their own class path, isolated from Buck's.

        There was a bug caused by adding annotation processors and setting the processorpath
        for javac. In that case, Buck's version of guava would leak into the classpath of the
        annotation processor causing it to fail to run and all heck breaking loose."""

        test_data = os.path.join(
            "test",
            "com",
            "facebook",
            "buck",
            "cli",
            "bootstrapper",
            "testdata",
            "old_guava",
        )

        with ProjectWorkspace(test_data) as workspace:
            returncode = workspace.run_buck(
                "build",
                "//:example",
            )
            self.assertEquals(0, returncode)


if __name__ == "__main__":
    unittest.main()
