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

package com.facebook.buck.junit;

import org.junit.runners.model.Statement;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class SameThreadFailOnTimeout extends Statement {
  private final ExecutorService executor;
  private final long timeout;
  private final String testName;
  private final Callable<Throwable> callable;

  public SameThreadFailOnTimeout(
      ExecutorService executor,
      long timeout,
      String testName,
      final Statement next) {
    this.executor = executor;
    this.timeout = timeout;
    this.testName = testName;
    this.callable = new Callable<Throwable>() {
      @Override
      public Throwable call() {
        try {
          next.evaluate();
          return null;
        } catch (Throwable throwable) {
          return throwable;
        }
      }
    };
  }

  @Override
  public void evaluate() throws Throwable {
    Future<Throwable> submitted = executor.submit(callable);
    try {
      Throwable result = submitted.get(timeout, TimeUnit.MILLISECONDS);
      if (result != null) {
        throw result;
      }
    } catch (TimeoutException e) {
      submitted.cancel(true);
      // The default timeout doesn't indicate which test was running.
      String message = String.format(
          "test %s timed out after %d milliseconds",
          testName,
          timeout);

      throw new Exception(message);
    }
  }
}
