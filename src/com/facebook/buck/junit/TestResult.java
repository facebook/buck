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

import com.facebook.buck.test.result.type.ResultType;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of an individual test method in JUnit. Similar to {@link Result}, except that it always
 * corresponds to exactly one test method.
 */
final class TestResult {

  private static final String ENCODING = "UTF-8";

  final String testClassName;
  final String testMethodName;
  final long runTime;
  final ResultType type;
  final /* @Nullable */ Failure failure;
  final /* @Nullable */ String stdOut;
  final /* @Nullable */ String stdErr;

  public TestResult(String testClassName,
      String testMethodName,
      long runTime,
      ResultType type,
      /* @Nullable */ Failure failure,
      /* @Nullable */ String stdOut,
      /* @Nullable */ String stdErr) {
    this.testClassName = testClassName;
    this.testMethodName = testMethodName;
    this.runTime = runTime;
    this.type = type;
    this.failure = failure;
    this.stdOut = stdOut;
    this.stdErr = stdErr;
  }

  public boolean isSuccess() {
    return type == ResultType.SUCCESS;
  }

  /**
   * Creates RunListener that will prepare individual result for each test
   * and store it to results list afterwards.
   */
  static RunListener createSingleTestResultRunListener(final List<TestResult> results) {
    return new RunListener() {

      private PrintStream originalOut, originalErr, stdOutStream, stdErrStream;
      private ByteArrayOutputStream rawStdOutBytes, rawStdErrBytes;
      private Result result;
      private RunListener resultListener;
      private final List<Failure> assumptionFailures = new ArrayList<>();

      // To help give a reasonable (though imprecise) guess at the runtime for unpaired failures
      private long startTime = System.currentTimeMillis();

      @Override
      public void testStarted(Description description) throws Exception {
        // Create an intermediate stdout/stderr to capture any debugging statements (usually in the
        // form of System.out.println) the developer is using to debug the test.
        originalOut = System.out;
        originalErr = System.err;
        rawStdOutBytes = new ByteArrayOutputStream();
        rawStdErrBytes = new ByteArrayOutputStream();
        stdOutStream = new PrintStream(
            rawStdOutBytes, true /* autoFlush */, ENCODING);
        stdErrStream = new PrintStream(
            rawStdErrBytes, true /* autoFlush */, ENCODING);
        System.setOut(stdOutStream);
        System.setErr(stdErrStream);

        // Prepare single-test result.
        result = new Result();
        resultListener = result.createListener();
        resultListener.testRunStarted(description);
        resultListener.testStarted(description);
      }

      @Override
      public void testFinished(Description description) throws Exception {
        // Shutdown single-test result.
        resultListener.testFinished(description);
        resultListener.testRunFinished(result);
        resultListener = null;

        // Restore the original stdout/stderr.
        System.setOut(originalOut);
        System.setErr(originalErr);

        // Get the stdout/stderr written during the test as strings.
        stdOutStream.flush();
        stdErrStream.flush();

        int numFailures = result.getFailureCount();
        String className = description.getClassName();
        String methodName = description.getMethodName();
        // In practice, I have seen one case of a test having more than one failure:
        // com.xtremelabs.robolectric.shadows.H2DatabaseTest#shouldUseH2DatabaseMap() had 2
        // failures.  However, I am not sure what to make of it, so we let it through.
        if (numFailures < 0) {
          throw new IllegalStateException(String.format(
              "Unexpected number of failures while testing %s#%s(): %d (%s)",
              className,
              methodName,
              numFailures,
              result.getFailures()));
        }

        Failure failure;
        ResultType type;
        if (assumptionFailures.size() > 0) {
          failure = assumptionFailures.get(0);
          type = ResultType.ASSUMPTION_VIOLATION;
        } else if (numFailures == 0) {
          failure = null;
          type = ResultType.SUCCESS;
        } else {
          failure = result.getFailures().get(0);
          type = ResultType.FAILURE;
        }

        String stdOut = rawStdOutBytes.size() == 0 ? null : rawStdOutBytes.toString(ENCODING);
        String stdErr = rawStdErrBytes.size() == 0 ? null : rawStdErrBytes.toString(ENCODING);

        results.add(new TestResult(className,
            methodName,
            result.getRunTime(),
            type,
            failure,
            stdOut,
            stdErr));
      }

      /**
       * The regular listener we created from the singular result, in this class, will not by
       * default treat assumption failures as regular failures, and will not store them.  As a
       * consequence, we store them ourselves!
       */
      @Override
      public void testAssumptionFailure(Failure failure) {
        assumptionFailures.add(failure);
        if (resultListener != null) {
          // Left in only to help catch future bugs -- right now this does nothing.
          resultListener.testAssumptionFailure(failure);
        }
      }

      @Override
      public void testFailure(Failure failure) throws Exception {
        if (resultListener == null) {
          recordUnpairedFailure(failure);
        } else {
          resultListener.testFailure(failure);
        }
      }

      @Override
      public void testIgnored(Description description) throws Exception {
        if (resultListener != null) {
          resultListener.testIgnored(description);
        }
      }

      /**
       * It's possible to encounter a Failure before we've started any tests (and therefore before
       * testStarted() has been called).  The known example is a @BeforeClass that throws an
       * exception, but there may be others.
       * <p>
       * Recording these unexpected failures helps us propagate failures back up to the "buck test"
       * process.
       */
      private void recordUnpairedFailure(Failure failure) {
        long runtime = System.currentTimeMillis() - startTime;
        Description description = failure.getDescription();
        results.add(new TestResult(
            description.getClassName(),
            description.getMethodName(),
            runtime,
            ResultType.FAILURE,
            failure,
            null,
            null));
      }
    };
  }
}
