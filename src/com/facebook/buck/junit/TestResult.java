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

package com.facebook.buck.junit;

import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

/**
 * Result of an individual test method in JUnit. Similar to {@link Result}, except that it always
 * corresponds to exactly one test method.
 */
final class TestResult {

  private static final String ENCODING = "UTF-8";

  final String testClassName;
  final String testMethodName;
  final long runTime;
  final /* @Nullable */ Failure failure;
  final /* @Nullable */ String stdOut;
  final /* @Nullable */ String stdErr;

  public TestResult(String testClassName,
      String testMethodName,
      long runTime,
      Failure failure,
      /* @Nullable */ String stdOut,
      /* @Nullable */ String stdErr) {
    this.testClassName = testClassName;
    this.testMethodName = testMethodName;
    this.runTime = runTime;
    this.failure = failure;
    this.stdOut = stdOut;
    this.stdErr = stdErr;
  }

  public boolean isSuccess() {
    return failure == null;
  }

  /**
   * Runs the specified test method using the specified test runner.
   */
  static TestResult runTestMethod(Class<?> clazz, String methodName, JUnitCore testRunner)
      throws UnsupportedEncodingException {
    Request request = Request.method(clazz, methodName);

    // Create an intermediate stdout/stderr to capture any debugging statements (usually in the
    // form of System.out.println) the developer is using to debug the test.
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream rawStdOutBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream rawStdErrBytes = new ByteArrayOutputStream();
    PrintStream stdOutStream = new PrintStream(
        rawStdOutBytes, true /* autoFlush */, ENCODING);
    PrintStream stdErrStream = new PrintStream(
        rawStdErrBytes, true /* autoFlush */, ENCODING);
    System.setOut(stdOutStream);
    System.setErr(stdErrStream);

    // Run the test!
    Result result = testRunner.run(request);

    // Restore the original stdout/stderr.
    System.setOut(originalOut);
    System.setErr(originalErr);

    // Get the stdout/stderr written during the test as strings.
    stdOutStream.flush();
    stdErrStream.flush();

    int numFailures = result.getFailureCount();
    // In practice, I have seen one case of a test having more than one failure:
    // com.xtremelabs.robolectric.shadows.H2DatabaseTest#shouldUseH2DatabaseMap() had 2 failures.
    // However, I am not sure what to make of it, so we let it through.
    if (numFailures < 0) {
      throw new IllegalStateException(String.format(
          "Unexpected number of failures while testing %s#%s(): %d (%s)",
          clazz.getName(),
          methodName,
          numFailures,
          result.getFailures()));
    }
    Failure failure = numFailures == 0 ? null : result.getFailures().get(0);

    String stdOut = rawStdOutBytes.size() == 0 ? null : rawStdOutBytes.toString(ENCODING);
    String stdErr = rawStdErrBytes.size() == 0 ? null : rawStdErrBytes.toString(ENCODING);
    return new TestResult(clazz.getName(),
        methodName,
        result.getRunTime(),
        failure,
        stdOut,
        stdErr);
  }
}
