"""Run all unit tests."""

import glob
import os
import sys
import unittest


def main():
    test_dir = os.path.dirname(os.path.abspath(__file__))
    test_files = glob.glob(os.path.join(test_dir, 'test_*.py'))
    test_names = [os.path.basename(f)[:-3] for f in test_files]

    sys.path.insert(0, os.path.join(test_dir, '..'))

    suite = unittest.defaultTestLoader.loadTestsFromNames(test_names)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    sys.exit(1 if (result.errors or result.failures) else 0)

if __name__ == '__main__':
    main()
