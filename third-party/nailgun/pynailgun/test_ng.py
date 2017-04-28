import subprocess
import os
import time
import StringIO
import unittest
import tempfile
import shutil

import pkg_resources

from pynailgun import NailgunException, NailgunConnection


POSSIBLE_NAILGUN_CODES_ON_NG_STOP = [
    NailgunException.CONNECT_FAILED,
    NailgunException.CONNECTION_BROKEN,
    NailgunException.UNEXPECTED_CHUNKTYPE,
]


if os.name == 'posix':
    def transport_exists(transport_file):
        return os.path.exists(transport_file)


@unittest.skipUnless(os.name == 'posix', 'Only works on posix')
class TestNailgunConnection(unittest.TestCase):
    def setUp(self):
        self.setUpTransport()
        self.startNailgun()

    def setUpTransport(self):
        self.tmpdir = tempfile.mkdtemp()
        if os.name == 'posix':
            self.transport_file = os.path.join(self.tmpdir, 'sock')
            self.transport_address = 'local:{0}'.format(self.transport_file)
        else:
            raise NotImplementedError()

    def getNailgunUberJar(self):
        stream = pkg_resources.resource_stream(__name__, 'nailgun-uber.jar')
        uber_jar_path = os.path.join(self.tmpdir, 'nailgun-uber.jar')
        with open(uber_jar_path, 'wb') as f:
            f.write(stream.read())
        return uber_jar_path

    def startNailgun(self):
        if os.name == 'posix':
            def preexec_fn():
                # Close any open file descriptors to further separate buckd from its
                # invoking context (e.g. otherwise we'd hang when running things like
                # `ssh localhost buck clean`).
                dev_null_fd = os.open("/dev/null", os.O_RDWR)
                os.dup2(dev_null_fd, 0)
                os.dup2(dev_null_fd, 1)
                os.dup2(dev_null_fd, 2)
                os.close(dev_null_fd)
            creationflags = 0
        else:
            raise NotImplementedError()

        self.ng_server_process = subprocess.Popen(
            ['java', '-jar', self.getNailgunUberJar(), self.transport_address],
            close_fds=True,
            preexec_fn=preexec_fn,
            creationflags=creationflags,
        )

        self.assertIsNone(self.ng_server_process.poll())

        # Give Java some time to create the listening socket.
        for i in range(0, 300):
            if not transport_exists(self.transport_file):
                time.sleep(0.01)

        self.assertTrue(transport_exists(self.transport_file))

    def test_nailgun_stats_and_stop(self):
        for i in range(1, 5):
            output = StringIO.StringIO()
            with NailgunConnection(
                    self.transport_address,
                    stderr=None,
                    stdin=None,
                    stdout=output) as c:
                exit_code = c.send_command('ng-stats')
                self.assertEqual(exit_code, 0)
            actual_out = output.getvalue().strip()
            expected_out = 'com.martiansoftware.nailgun.builtins.NGServerStats: {0}/1'.format(i)
            self.assertEqual(actual_out, expected_out)

        try:
            with NailgunConnection(
                    self.transport_address,
                    cwd=os.getcwd(),
                    stderr=None,
                    stdin=None,
                    stdout=None) as c:
                c.send_command('ng-stop')
        except NailgunException as e:
            self.assertIn(e.code, POSSIBLE_NAILGUN_CODES_ON_NG_STOP)

        self.ng_server_process.wait()
        self.assertEqual(self.ng_server_process.poll(), 0)

    def tearDown(self):
        if self.ng_server_process.poll() is None:
            # some test has failed, ng-server was not stopped. killing it
            self.ng_server_process.kill()
        shutil.rmtree(self.tmpdir)


if __name__ == '__main__':
    unittest.main()
