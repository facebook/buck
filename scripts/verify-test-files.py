#!/usr/bin/env python
"""
Enforce that Java files which look like they contain JUnit test cases
are referenced by a test BUCK target. In other words: find tests that
will not run with `buck test --all`
"""
from __future__ import absolute_import
from __future__ import division
from __future__ import print_function

import argparse
import codecs
import fnmatch
import json
import os
import platform
import subprocess
import sys
import tempfile


IGNORE_PREFIXES = [
  'buck-out',
  'build-ij',
  'src/com/facebook/buck/testrunner',
  'test/com/facebook/buck/testutil/endtoend/EndToEndRunner',
  # t13833822
  'tools/ideabuck/tests/integration',
]
# Due to path length issues during build on Windows the apple test target is
# only created on OSx.
if sys.platform != "darwin":
    IGNORE_PREFIXES.extend([
        'test/com/facebook/buck/apple'
    ])
# make the prefixes platform-specific
IGNORE_PREFIXES = [os.path.join(*p.split('/')) for p in IGNORE_PREFIXES]


def isPathIgnored(path):
    for prefix in IGNORE_PREFIXES:
        if path.startswith(prefix):
            return True
    return False


def containsJUnitImport(path):
    with codecs.open(path, "r", "utf-8") as f:
        for l in f.readlines():
            if "import org.junit.Test" in l:
                return True
    return False


def getTestFiles(repo_root):
    test_files = []
    for root, dirs, files in os.walk(repo_root):
        for f in files:
            absolute_path = os.path.join(root, f)
            if not f.endswith(".java"):
                continue
            if 'testdata' in absolute_path:
                continue
            if not containsJUnitImport(absolute_path):
                continue
            relative_path = os.path.relpath(absolute_path, repo_root)
            if isPathIgnored(relative_path):
                continue
            assert os.path.exists(absolute_path), "%s doesn't exist" % line
            test_files.append(relative_path)
    return sorted(test_files)


def getOwningRulesData(repo_root, test_files):
    t_fd, t_name = tempfile.mkstemp(text=True)
    try:
        with open(t_name, "w") as t_file:
            for f in test_files:
                t_file.write(f)
                t_file.write("\n")
        os.close(t_fd)
        env = dict(os.environ.items())
        env['NO_BUCKD'] = '1'
        if platform.system() == 'Windows':
            buck_path = os.path.join(repo_root, 'bin', 'buck.bat')
            cmd_prefix = ['cmd.exe', '/C', buck_path]
        else:
            buck_path = os.path.join(repo_root, 'bin', 'buck')
            cmd_prefix = [buck_path]
        cmd_output = run_process(
            cmd_prefix + ['targets', '--json',
                          '--referenced-file', '@' + t_name],
            env=env,
            cwd=repo_root)
        # Drop anything that appears before the JSON output.
        cmd_output = cmd_output[cmd_output.index("["):]
        return json.loads(cmd_output)
    except ValueError as e:
        print('Problem parsing result to json', cmd_output)
        raise e
    finally:
        if os.path.exists(t_name):
            os.unlink(t_name)


def findUnreferencedTestFiles(test_files, owning_rules):
    referenced_test_files = set()
    for rule in owning_rules:
        base_path = rule['buck.base_path']
        rule_type = rule['buck.type']
        # On windows base_path is still unix-style
        base_path = os.path.join(*base_path.split('/'))
        if not rule_type.endswith('_test'):
            continue
        for src in rule['srcs']:
            referenced_test_files.add(os.path.join(base_path, src))
    return set(test_files).difference(referenced_test_files)


def findRepoRoot(cwd):
    while os.path.exists(cwd):
        if os.path.exists(os.path.join(cwd, '.buckconfig')):
            return cwd
        cwd = os.path.dirname(cwd)
    raise Exception('Could not locate buck repo root.')


def run_process(*args, **kwargs):
    process = subprocess.Popen(stdout=subprocess.PIPE, *args, **kwargs)
    stdout, _ = process.communicate()
    retcode = process.poll()
    if retcode != 0:
        raise Exception('Error %s running %s' % (retcode, args))
    return stdout


def getMisplacedBuckFilesInTestdata(repo_root):
    buck_files = []
    for root, dirs, files in os.walk(repo_root):
        for f in files:
            full_path = os.path.join(root, f)
            if not f == 'BUCK':
                continue
            if 'testdata' not in full_path:
                continue
            buck_files.append(full_path)
    return buck_files


def main():
    repo_root = findRepoRoot(os.getcwd())
    test_files = getTestFiles(repo_root)
    owning_rules = getOwningRulesData(repo_root, test_files)
    unreferenced_files = findUnreferencedTestFiles(test_files, owning_rules)
    for f in unreferenced_files:
        print(f, "looks like a test file, but is not covered by a test rule.")

    misplaced_buck_files = getMisplacedBuckFilesInTestdata(repo_root)
    for b in misplaced_buck_files:
        print(b, "lives in `testdata`, you should rename it to BUCK.fixture " +
              "otherwise it will be accidentally picked up by the build.")

    if len(unreferenced_files) == 0 and len(misplaced_buck_files) == 0:
        print('No unreferenced test files found, all is good.')
        return 0
    else:
        return 1


if __name__ == '__main__':
    sys.exit(main())
