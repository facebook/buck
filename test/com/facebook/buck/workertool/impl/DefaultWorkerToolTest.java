/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.workertool.impl;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.types.Unit;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class DefaultWorkerToolTest {

  private static final int TIMEOUT = 100;
  private static final TimeUnit TIMEOUT_UNIT = TimeUnit.MILLISECONDS;

  private static final ExecutorService TEST_EXECUTOR =
      MostExecutors.newSingleThreadExecutor(
          DefaultWorkerToolTest.class.getSimpleName() + "_executor");

  @Rule public Timeout globalTestTimeout = Timeout.seconds(10);

  @Test
  public void runWithTimeoutSuccessful() throws ExecutionException {
    Unit result = runWithTimeout(() -> Unit.UNIT, TIMEOUT);
    assertThat(result, equalTo(Unit.UNIT));
  }

  @Test
  public void runWithTimeoutExpired() {
    ExecutionException executionException =
        assertThrows(
            ExecutionException.class,
            () ->
                runWithTimeout(
                    () -> {
                      TIMEOUT_UNIT.sleep(TIMEOUT + 10);
                      return Unit.UNIT;
                    },
                    TIMEOUT));

    Throwable cause = executionException.getCause();
    assertThat(cause, notNullValue());
    assertThat(cause, instanceOf(TimeoutException.class));
    assertThat(
        executionException.getMessage(), equalTo("Timeout of " + TIMEOUT + "ms has been exceeded"));
  }

  @Test
  public void runWithTimeoutExecutionException() {
    ExecutionException executionException =
        assertThrows(
            ExecutionException.class,
            () ->
                runWithTimeout(
                    () -> {
                      throw new IllegalStateException("something went wrong");
                    },
                    TIMEOUT));

    Throwable cause = executionException.getCause();
    assertThat(cause, notNullValue());
    assertThat(cause, instanceOf(IllegalStateException.class));
    assertThat(cause.getMessage(), equalTo("something went wrong"));
  }

  @Test
  public void runWithTimeoutInterrupted() {
    ExecutionException executionException =
        assertThrows(
            ExecutionException.class,
            () ->
                runWithTimeout(
                    () -> {
                      TIMEOUT_UNIT.sleep(TIMEOUT);
                      throw new InterruptedException("test interrupted exception");
                    },
                    TIMEOUT + 10));

    Throwable cause = executionException.getCause();
    assertThat(cause, notNullValue());
    assertThat(cause, instanceOf(InterruptedException.class));
    assertThat(cause.getMessage(), equalTo("test interrupted exception"));
  }

  @Test
  public void resourcesAreClosedForATimedOutOperation() {
    ExecutionException executionException =
        assertThrows(
            ExecutionException.class,
            () ->
                runWithTimeout(
                    () -> {
                      // runs 3 times longer than timeout
                      TIMEOUT_UNIT.sleep(3 * TIMEOUT);
                      return Unit.UNIT;
                    },
                    TIMEOUT));

    Throwable cause = executionException.getCause();
    assertThat(cause, notNullValue());
    assertThat(cause, instanceOf(TimeoutException.class));
    assertThat(
        executionException.getMessage(), equalTo("Timeout of " + TIMEOUT + "ms has been exceeded"));
  }

  static <T> T runWithTimeout(Callable<T> callable, long timeout) throws ExecutionException {
    Future<T> future = TEST_EXECUTOR.submit(callable);
    try {
      return DefaultWorkerToolExecutor.getFutureResult(
          future, timeout, DefaultWorkerToolTest.TIMEOUT_UNIT);
    } finally {
      assertThat(future.isDone(), is(true));
    }
  }
}
