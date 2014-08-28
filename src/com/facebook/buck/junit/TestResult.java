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

/**
 * Result of an individual test method in JUnit. Similar to {@link org.junit.runner.Result}, except
 * that it always corresponds to exactly one test method.
 */
final class TestResult {
  final String testClassName;
  final String testMethodName;
  final long runTime;
  final ResultType type;
  final/* @Nullable */Throwable failure;
  final /* @Nullable */ String stdOut;
  final /* @Nullable */ String stdErr;

  public TestResult(String testClassName,
      String testMethodName,
      long runTime,
      ResultType type,
      /* @Nullable */Throwable failure,
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
}
