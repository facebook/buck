#!/usr/local/bin/python2.6 -tt
#
# Copyright 2004-present Facebook.  All rights reserved.
#
"""
This file contains the main module code for buck python test programs.

By default, this is the main module for all python_test() rules.  However,
rules can also specify their own custom main_module.  If you write your own
main module, you can import this module as tools.test.stubs.fbpyunit, to access
any of its code to help implement your main module.
"""

from __future__ import print_function

import json
import optparse
import os
import sys
import time
import re
import unittest
import traceback
import contextlib


class TestStatus(object):

    ABORTED = 'FAILURE'
    PASSED = 'SUCCESS'
    FAILED = 'FAILURE'
    EXPECTED_FAILURE = 'SUCCESS'
    UNEXPECTED_SUCCESS = 'FAILURE'
    SKIPPED = 'SUCCESS'


class TeeStream(object):

    def __init__(self, *streams):
        self._streams = streams

    def write(self, data):
        for stream in self._streams:
            stream.write(data)

    def flush(self):
        for stream in self._streams:
            stream.flush()

    def isatty(self):
        return False


class CallbackStream(object):

    def __init__(self, callback):
        self._callback = callback

    def write(self, data):
        self._callback(data)

    def flush(self):
        pass

    def isatty(self):
        return False


class FbJsonTestResult(unittest._TextTestResult):
    """
    Our own TestResult class that outputs data in a format that can be easily
    parsed by fbmake's test runner.
    """

    def __init__(self, stream, descriptions, verbosity):
        super(FbJsonTestResult, self).__init__(stream, descriptions, verbosity)
        self._results = []
        self._current_test = None
        self._saved_stdout = sys.stdout
        self._saved_stderr = sys.stderr

    def getResults(self):
        return self._results

    def startTest(self, test):
        super(FbJsonTestResult, self).startTest(test)

        sys.stdout = CallbackStream(self.addStdout)
        sys.stderr = CallbackStream(self.addStderr)

        self._current_test = test
        self._test_start_time = time.time()
        self._current_status = TestStatus.ABORTED
        self._messages = []
        self._stacktrace = None
        self._stdout = ''
        self._stderr = ''

    def stopTest(self, test):
        sys.stdout = self._saved_stdout
        sys.stderr = self._saved_stderr

        super(FbJsonTestResult, self).stopTest(test)

        self._results.append({
            'testCaseName': test._testMethodName,
            'testCase': '{0}.{1}'.format(
                test.__class__.__module__,
                test.__class__.__name__),
            'type': self._current_status,
            'time': int((time.time() - self._test_start_time) * 1000),
            'message': os.linesep.join(self._messages),
            'stacktrace': self._stacktrace,
            'stdOut': self._stdout,
            'stdErr': self._stderr,
        })

        self._current_test = None

    @contextlib.contextmanager
    def _withTest(self, test):
        self.startTest(test)
        yield
        self.stopTest(test)

    def _setStatus(self, test, status, message=None, stacktrace=None):
        assert test == self._current_test
        self._current_status = status
        self._stacktrace = stacktrace
        if message is not None:
            if message.endswith(os.linesep):
                message = message[:-1]
            self._messages.append(message)

    def setStatus(self, test, status, message=None, stacktrace=None):
        # addError() may be called outside of a test if one of the shared
        # fixtures (setUpClass/tearDownClass/setUpModule/tearDownModule)
        # throws an error.
        #
        # In this case, create a fake test result to record the error.
        if self._current_test is None:
            with self._withTest(test):
                self._setStatus(test, status, message, stacktrace)
        else:
            self._setStatus(test, status, message, stacktrace)

    def setException(self, test, status, excinfo):
        exctype, value, tb = excinfo
        self.setStatus(
            test, status,
            '{0}: {1}'.format(exctype.__name__, value),
            ''.join(traceback.format_tb(tb)))

    def addSuccess(self, test):
        super(FbJsonTestResult, self).addSuccess(test)
        self.setStatus(test, TestStatus.PASSED)

    def addError(self, test, err):
        super(FbJsonTestResult, self).addError(test, err)
        self.setException(test, TestStatus.ABORTED, err)

    def addFailure(self, test, err):
        super(FbJsonTestResult, self).addFailure(test, err)
        self.setException(test, TestStatus.FAILED, err)

    def addSkip(self, test, reason):
        super(FbJsonTestResult, self).addSkip(test, reason)
        self.setStatus(test, TestStatus.SKIPPED, 'Skipped: %s' % (reason,))

    def addExpectedFailure(self, test, err):
        super(FbJsonTestResult, self).addExpectedFailure(test, err)
        self.setException(test, TestStatus.EXPECTED_FAILURE, err)

    def addUnexpectedSuccess(self, test):
        super(FbJsonTestResult, self).addUnexpectedSuccess(test)
        self.setStatus(test, TestStatus.UNEXPECTED_SUCCESS,
                       'Unexpected success')

    def addStdout(self, val):
        self._stdout += val

    def addStderr(self, val):
        self._stderr += val


class FbJsonTestRunner(unittest.TextTestRunner):

    def _makeResult(self):
        return FbJsonTestResult(
            self.stream,
            self.descriptions,
            self.verbosity)


class RegexTestLoader(unittest.TestLoader):

    def __init__(self, regex=None):
        super(unittest.TestLoader, self).__init__()
        self.regex = regex

    def getTestCaseNames(self, testCaseClass):
        """
        Return a sorted sequence of method names found within testCaseClass
        """

        testFnNames = super(RegexTestLoader, self).getTestCaseNames(
            testCaseClass)
        matched = []
        for attrname in testFnNames:
            fullname = '{0}.{1}#{2}'.format(
                testCaseClass.__class__.__module__,
                testCaseClass.__class__.__name__,
                attrname)
            if self.regex is None or re.search(self.regex, fullname):
                matched.append(attrname)
        return matched


class Loader(object):

    def __init__(self, modules, regex=None):
        self.modules = modules
        self.regex = regex

    def load_all(self):
        loader = RegexTestLoader(self.regex)
        test_suite = unittest.TestSuite()
        for module_name in self.modules:
            __import__(module_name, level=0)
            module = sys.modules[module_name]
            module_suite = loader.loadTestsFromModule(module)
            test_suite.addTest(module_suite)
        return test_suite

    def load_args(self, args):
        loader = RegexTestLoader(self.regex)

        suites = []
        for arg in args:
            suite = loader.loadTestsFromName(arg)
            # loadTestsFromName() can only process names that refer to
            # individual test functions or modules.  It can't process package
            # names.  If there were no module/function matches, check to see if
            # this looks like a package name.
            if suite.countTestCases() != 0:
                suites.append(suite)
                continue

            # Load all modules whose name is <arg>.<something>
            prefix = arg + '.'
            for module in self.modules:
                if module.startswith(prefix):
                    suite = loader.loadTestsFromName(module)
                    suites.append(suite)

        return loader.suiteClass(suites)


class MainProgram(object):
    '''
    This class implements the main program.  It can be subclassed by
    users who wish to customize some parts of the main program.
    (Adding additional command line options, customizing test loading, etc.)
    '''
    DEFAULT_VERBOSITY = 2

    def __init__(self, argv):
        self.init_option_parser()
        self.parse_options(argv)

    def init_option_parser(self):
        usage = '%prog [options] [TEST] ...'
        op = optparse.OptionParser(usage=usage, add_help_option=False)
        self.option_parser = op

        op.add_option(
            '-o', '--output',
            help='Write results to a file in a JSON format to be read by Buck')
        op.add_option(
            '-l', '--list-tests', action='store_true', dest='list',
            default=False, help='List tests and exit')
        op.add_option(
            '-q', '--quiet', action='count', default=0,
            help='Decrease the verbosity (may be specified multiple times)')
        op.add_option(
            '-v', '--verbosity',
            action='count', default=self.DEFAULT_VERBOSITY,
            help='Increase the verbosity (may be specified multiple times)')
        op.add_option(
            '-r', '--regex', default=None,
            help='Regex to apply to tests, to only run those tests')
        op.add_option(
            '-?', '--help', action='help',
            help='Show this help message and exit')

    def parse_options(self, argv):
        self.options, self.test_args = self.option_parser.parse_args(argv[1:])
        self.options.verbosity -= self.options.quiet

    def create_loader(self):
        import __test_modules__
        return Loader(__test_modules__.TEST_MODULES, self.options.regex)

    def load_tests(self):
        loader = self.create_loader()
        if self.test_args:
            return loader.load_args(self.test_args)
        else:
            return loader.load_all()

    def get_test_names(self, test_suite):
        names = []

        for test in test_suite:
            if isinstance(test, unittest.TestSuite):
                names.extend(self.get_test_names(test))
            else:
                names.append(str(test))

        return names

    def run(self):
        test_suite = self.load_tests()

        if self.options.list:
            for test in self.get_test_names(test_suite):
                print(test)
            return 0
        else:
            result = self.run_tests(test_suite)
            if self.options.output is not None:
                with open(self.options.output, 'w') as f:
                    json.dump(result.getResults(), f, indent=4, sort_keys=True)
            return 0
            #return 0 if result.wasSuccessful() else 1

    def run_tests(self, test_suite):
        # Install a signal handler to catch Ctrl-C and display the results
        # (but only if running >2.6).
        if sys.version_info[0] > 2 or sys.version_info[1] > 6:
            unittest.installHandler()

        # Run the tests
        runner = FbJsonTestRunner(verbosity=self.options.verbosity)
        result = runner.run(test_suite)

        return result


def main(argv):
    return MainProgram(sys.argv).run()


if __name__ == '__main__':
    sys.exit(main(sys.argv))
