/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.testrunner;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.runners.model.Statement;

class SameThreadFailOnTimeout extends Statement {
  private final ExecutorService executor;
  private final long timeout;
  private final String testName;
  private final Callable<Throwable> callable;

  public SameThreadFailOnTimeout(
      ExecutorService executor, long timeout, String testName, Statement next) {
    this.executor = executor;
    this.timeout = timeout;
    this.testName = testName;
    this.callable =
        () -> {
          try {
            next.evaluate();
            return null;
          } catch (Throwable throwable) {
            return throwable;
          }
        };
  }

  @Override
  public void evaluate() throws Throwable {
    Future<Throwable> submitted = executor.submit(callable);
    try {
      Throwable result = submitted.get(timeout, TimeUnit.MILLISECONDS);
      if (result == null) {
        return;
      }
      if (result instanceof TimeoutException) {
        throw new Exception("A timeout occurred inside of the test case", result);
      }
      throw result;
    } catch (TimeoutException e) {
      System.err.printf("Dumping threads for timed-out test %s:%n", testName);
      for (Map.Entry<Thread, StackTraceElement[]> t : Thread.getAllStackTraces().entrySet()) {
        Thread thread = t.getKey();
        System.err.printf("\"%s\" #%d%n", thread.getName(), thread.getId());
        System.err.printf("\tjava.lang.Thread.State: %s%n", thread.getState());
        for (StackTraceElement element : t.getValue()) {
          System.err.printf("\t\t at %s%n", element);
        }
      }

      submitted.cancel(true);

      // The default timeout doesn't indicate which test was running.
      String message = String.format("test %s timed out after %d milliseconds", testName, timeout);

      throw new Exception(message);
    }
  }
}
