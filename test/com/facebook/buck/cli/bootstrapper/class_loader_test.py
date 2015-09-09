import os
import shutil
import subprocess
import sys
import tempfile
import unittest


class ClassLoaderTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        template_test_data_directory = os.path.join(
            'test',
            'com',
            'facebook',
            'buck',
            'cli',
            'bootstrapper',
            'testdata',
            'old_guava')
        self.test_data_directory = os.path.join(self.temp_dir, 'old_guava')
        shutil.copytree(template_test_data_directory, self.test_data_directory)
        for root, dirs, files in os.walk(self.test_data_directory):
            for f in files:
                filename, fileext = os.path.splitext(f)
                if fileext == '.fixture':
                    os.rename(os.path.join(root, f), os.path.join(root, filename))

    def tearDown(self):
        shutil.rmtree(self.temp_dir)

    def test_should_not_pollute_classpath_when_processor_path_is_set(self):
        """
        Tests that annotation processors get their own class path, isolated from Buck's.

        There was a bug caused by adding annotation processors and setting the processorpath
        for javac. In that case, Buck's version of guava would leak into the classpath of the
        annotation processor causing it to fail to run and all heck breaking loose."""
        root_directory = os.getcwd()
        buck_path = os.path.join(root_directory, 'bin', 'buck')
        # Pass thru our environment, except disabling buckd so that we can be sure the right buck
        # is run.
        child_environment = dict(os.environ)
        child_environment["NO_BUCKD"] = "1"

        proc = subprocess.Popen(
            [buck_path, 'build', '//:example'],
            cwd=self.test_data_directory,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=child_environment)

        stdout, stderr = proc.communicate()

        # Copy output through to unittest's output so failures are easy to debug. Can't just
        # provide sys.stdout/sys.stderr to Popen because unittest has replaced the streams with
        # things that aren't directly compatible with Popen.
        sys.stdout.write(stdout)
        sys.stdout.flush()
        sys.stderr.write(stderr)
        sys.stderr.flush()

        self.assertEquals(0, proc.returncode)

if __name__ == '__main__':
    unittest.main()
