#!/usr/bin/env python
# Copyright 2017-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.


"""
Verifies that an archive contains only allowed entries.
"""

import unittest
from zipfile import ZipFile

import pkg_resources

ALLOWED_ENTRIES = """
com/facebook/buck/core/util/log/appendablelogrecord/AppendableLogRecord.class
com/facebook/buck/jvm/java/runner/FileClassPathRunner.class
com/facebook/buck/test/result/type/ResultType.class
com/facebook/buck/test/selectors/Nullable.class
com/facebook/buck/test/selectors/PatternTestSelector.class
com/facebook/buck/test/selectors/SimpleTestSelector.class
com/facebook/buck/test/selectors/TestDescription.class
com/facebook/buck/test/selectors/TestSelector.class
com/facebook/buck/test/selectors/TestSelectorList$1.class
com/facebook/buck/test/selectors/TestSelectorList$Builder.class
com/facebook/buck/test/selectors/TestSelectorList.class
com/facebook/buck/test/selectors/TestSelectorParseException.class
com/facebook/buck/testrunner/BaseRunner.class
com/facebook/buck/testrunner/BuckBlockJUnit4ClassRunner$1.class
com/facebook/buck/testrunner/BuckBlockJUnit4ClassRunner.class
com/facebook/buck/testrunner/BuckXmlTestRunListener.class
com/facebook/buck/testrunner/CheckDependency.class
com/facebook/buck/testrunner/DelegateRunNotifier$1.class
com/facebook/buck/testrunner/DelegateRunNotifier$2.class
com/facebook/buck/testrunner/DelegateRunNotifier.class
com/facebook/buck/testrunner/DelegateRunnerWithTimeout$1.class
com/facebook/buck/testrunner/DelegateRunnerWithTimeout.class
com/facebook/buck/testrunner/InstrumentationMain.class
com/facebook/buck/testrunner/InstrumentationTestRunner$1.class
com/facebook/buck/testrunner/InstrumentationTestRunner$ApkLocationReceiver.class
com/facebook/buck/testrunner/InstrumentationTestRunner$Md5SumReceiver.class
com/facebook/buck/testrunner/InstrumentationTestRunner$Nullable.class
com/facebook/buck/testrunner/InstrumentationTestRunner.class
com/facebook/buck/testrunner/JUnitMain.class
com/facebook/buck/testrunner/JUnitRunner$1.class
com/facebook/buck/testrunner/JUnitRunner$2$1.class
com/facebook/buck/testrunner/JUnitRunner$2.class
com/facebook/buck/testrunner/JUnitRunner$RecordingFilter.class
com/facebook/buck/testrunner/JUnitRunner$TestListener.class
com/facebook/buck/testrunner/JUnitRunner.class
com/facebook/buck/testrunner/JulLogFormatter$1.class
com/facebook/buck/testrunner/JulLogFormatter.class
com/facebook/buck/testrunner/SameThreadFailOnTimeout.class
com/facebook/buck/testrunner/TestNGMain.class
com/facebook/buck/testrunner/TestNGRunner$1.class
com/facebook/buck/testrunner/TestNGRunner$FilteringAnnotationTransformer.class
com/facebook/buck/testrunner/TestNGRunner$JUnitReportReporterWithMethodParameters.class
com/facebook/buck/testrunner/TestNGRunner$TestListener.class
com/facebook/buck/testrunner/TestNGRunner.class
com/facebook/buck/testrunner/TestResult.class
com/facebook/buck/util/concurrent/MostExecutors$1.class
com/facebook/buck/util/concurrent/MostExecutors$NamedThreadFactory.class
com/facebook/buck/util/concurrent/MostExecutors.class
com/facebook/buck/util/environment/Architecture.class
com/facebook/buck/util/environment/Platform.class
com/facebook/buck/util/environment/PlatformType.class
"""


class TestAppend(unittest.TestCase):
    def test_allowed_jar_entries(self):

        with pkg_resources.resource_stream(__name__, "testrunner-bin-fixed.jar") as r:
            with ZipFile(r) as zip_file:
                for zip_file_entry in zip_file.namelist():
                    entry = zip_file_entry.encode("utf-8")
                    if not entry.endswith("/"):
                        self.assertTrue(
                            entry in ALLOWED_ENTRIES,
                            "Found unexpected entry in testrunner jar: %s" % entry,
                        )
