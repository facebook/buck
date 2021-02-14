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

import static com.facebook.buck.workertool.impl.DefaultWorkerToolUtils.runWithTimeout;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;

import com.facebook.buck.io.namedpipes.NamedPipeFactory;
import com.facebook.buck.io.namedpipes.NamedPipeWriter;
import com.facebook.buck.util.types.Unit;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Test;

public class DefaultWorkerToolUtilsTest {

  private static final int TIMEOUT = 10;
  private static final TimeUnit TIMEOUT_UNIT = TimeUnit.MILLISECONDS;

  private static final String TEST_EXECUTOR_NAME =
      DefaultWorkerToolUtilsTest.class.getSimpleName() + "_executor";

  @Test
  public void runWithTimeoutSuccessful() throws ExecutionException {
    Unit result = runWithTimeout(() -> Unit.UNIT, TEST_EXECUTOR_NAME, TIMEOUT, TIMEOUT_UNIT);
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
                    TEST_EXECUTOR_NAME,
                    TIMEOUT,
                    TIMEOUT_UNIT));

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
                    TEST_EXECUTOR_NAME,
                    TIMEOUT,
                    TIMEOUT_UNIT));

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
                    TEST_EXECUTOR_NAME,
                    TIMEOUT + 10,
                    TIMEOUT_UNIT));

    Throwable cause = executionException.getCause();
    assertThat(cause, notNullValue());
    assertThat(cause, instanceOf(InterruptedException.class));
    assertThat(cause.getMessage(), equalTo("test interrupted exception"));
  }

  @Test
  public void resourcesAreClosedForATimedOutOperation() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Unit> future =
        executor.submit(
            () -> {
              // runs 3 times longer than timeout
              TIMEOUT_UNIT.sleep(3 * TIMEOUT);
              return Unit.UNIT;
            });

    ExecutionException executionException =
        assertThrows(
            ExecutionException.class,
            () -> runWithTimeout(future, executor, TIMEOUT, TIMEOUT_UNIT));

    Throwable cause = executionException.getCause();
    assertThat(cause, notNullValue());
    assertThat(cause, instanceOf(TimeoutException.class));
    assertThat(
        executionException.getMessage(), equalTo("Timeout of " + TIMEOUT + "ms has been exceeded"));

    assertThat(future.isDone(), is(true));
    assertThat(executor.isShutdown(), is(true));
  }

  @Test
  public void resourcesAreClosedForATimedOutOperationForNamedPipesOperation() throws IOException {
    ExecutionException executionException;
    ExecutorService executor;
    Future<Unit> future;

    try (NamedPipeWriter namedPipeWriter = NamedPipeFactory.getFactory().createAsWriter()) {

      executor = Executors.newSingleThreadExecutor();
      future =
          executor.submit(
              () -> {
                // blocks on windows
                namedPipeWriter.getOutputStream();
                // blocks on posix
                // runs 3 times longer than timeout
                TIMEOUT_UNIT.sleep(3 * TIMEOUT);
                return Unit.UNIT;
              });

      executionException =
          assertThrows(
              ExecutionException.class,
              () -> runWithTimeout(future, executor, TIMEOUT, TIMEOUT_UNIT));
    }

    Throwable cause = executionException.getCause();
    assertThat(cause, notNullValue());
    assertThat(cause, instanceOf(TimeoutException.class));
    assertThat(
        executionException.getMessage(), equalTo("Timeout of " + TIMEOUT + "ms has been exceeded"));

    assertThat(future.isDone(), is(true));
    assertThat(executor.isShutdown(), is(true));
  }
}
