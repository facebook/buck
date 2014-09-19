/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.test.result.type.ResultType;

import org.junit.Test;

/**
 * Unit test for {@link TestResultSummary}.
 */
public class TestResultSummaryTest {

  @Test
  public void testSuccessSummary() {
    TestResultSummary testResultSummary = new TestResultSummary(
        "com.facebook.buck.rules.TestResultSummaryTest",
        "testGetters",
        ResultType.SUCCESS,
        123L,
        null /* message */,
        null /* stacktrace */,
        null /* stdOut */,
        null /* stdErr */);
    assertEquals("testGetters", testResultSummary.getTestName());
    assertTrue(testResultSummary.isSuccess());
    assertEquals(123L, testResultSummary.getTime());
    assertNull(testResultSummary.getMessage());
    assertNull(testResultSummary.getStacktrace());
    assertNull(testResultSummary.getStdOut());
    assertNull(testResultSummary.getStdErr());
    assertEquals("PASS  123ms com.facebook.buck.rules.TestResultSummaryTest#testGetters()",
        testResultSummary.toString());
  }

  @Test
  public void testFailureSummary() {
    String assertionFailedMessage = "expected:<PASS  123ms te[]tGetters(com.faceboo...> " +
        "but was:<PASS  123ms te[s]tGetters(com.faceboo...>";
    String stacktrace = "at org.junit.Assert.assertEquals(Assert.java:123)";
    String stdOut = "STDOUT: getTime() returned 452L";
    String stdErr = "STDERR: getTime() returned 452L";
    TestResultSummary testResultSummary = new TestResultSummary(
        "com.facebook.buck.rules.TestResultSummaryTest",
        "testGetters",
        ResultType.FAILURE,
        456L,
        assertionFailedMessage,
        stacktrace,
        stdOut,
        stdErr);
    assertEquals("testGetters", testResultSummary.getTestName());
    assertFalse(testResultSummary.isSuccess());
    assertEquals(456L, testResultSummary.getTime());
    assertEquals(assertionFailedMessage, testResultSummary.getMessage());
    assertEquals(stacktrace, testResultSummary.getStacktrace());
    assertEquals(stdOut, testResultSummary.getStdOut());
    assertEquals(stdErr, testResultSummary.getStdErr());
    assertEquals("FAIL  456ms com.facebook.buck.rules.TestResultSummaryTest#testGetters()",
        testResultSummary.toString());
  }

  @Test
  public void canTestEquality() {
    assertEquals(
        new TestResultSummary("Class", "test", ResultType.SUCCESS, 0L, "FAIL!", null, null, null),
        new TestResultSummary("Class", "test", ResultType.SUCCESS, 0L, "FAIL!", null, null, null));
    assertNotEquals(
        new TestResultSummary("Class", "test", ResultType.SUCCESS, 0L, "FAIL!", null, null, null),
        new TestResultSummary("Class", "test", ResultType.FAILURE, 0L, "FAIL!", null, null, null));
  }

}

