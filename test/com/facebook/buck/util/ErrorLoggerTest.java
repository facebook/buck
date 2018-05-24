/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util;

import static org.junit.Assert.*;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.handler.HumanReadableExceptionAugmentor;
import com.facebook.buck.util.exceptions.BuckExecutionException;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.junit.Test;

public class ErrorLoggerTest {
  static class LoggedErrors {
    @Nullable String userVisible = null;
    @Nullable String userVisibleInternal = null;
    @Nullable Throwable verbose = null;
  }

  @Test
  public void testRuntimeException() {
    LoggedErrors errors = logException(new RuntimeException("message"));
    assertNull(errors.userVisible);
    assertEquals("java.lang.RuntimeException: message", errors.userVisibleInternal);
  }

  @Test
  public void testHumanReadableException() {
    LoggedErrors errors = logException(new HumanReadableException("message"));
    assertNull(errors.userVisibleInternal);
    assertEquals("message", errors.userVisible);
  }

  @Test
  public void testWrappedException() {
    LoggedErrors errors =
        logException(new BuckExecutionException(new HumanReadableException("message")));
    assertNull(errors.userVisibleInternal);
    assertEquals("message", errors.userVisible);
  }

  @Test
  public void testExecutionException() {
    LoggedErrors errors =
        logException(new ExecutionException(new HumanReadableException("message")));
    assertNull(errors.userVisibleInternal);
    assertEquals("message", errors.userVisible);
  }

  @Test
  public void testUncheckedExecutionException() {
    LoggedErrors errors =
        logException(new UncheckedExecutionException(new HumanReadableException("message")));
    assertNull(errors.userVisibleInternal);
    assertEquals("message", errors.userVisible);
  }

  @Test
  public void testWrappedExceptionWithContext() {
    LoggedErrors errors =
        logException(new BuckExecutionException(new HumanReadableException("message"), "context"));
    assertNull(errors.userVisibleInternal);
    assertEquals("message\n" + "    context", errors.userVisible);
  }

  @Test
  public void addsErrorMessageAugmentations() {
    String rawMessage =
        "\u001B[1mmain.cpp:1:13: \u001B[0m\u001B[0;1;31merror: \u001B[0m\u001B[1mexpected '}'\u001B[0m\n"
            + "int main() {\n"
            + "\u001B[0;1;32m            ^\n"
            + "\u001B[0m\u001B[1mmain.cpp:1:12: \u001B[0m\u001B[0;1;30mnote: \u001B[0mto match this '{'\u001B[0m\n"
            + "int main() {\n"
            + "\u001B[0;1;32m           ^\n"
            + "\u001B[0m1 error generated.";
    String expected = rawMessage + "\n    context\nTry adding '}'!";

    LoggedErrors errors =
        logException(new BuckExecutionException(new HumanReadableException(rawMessage), "context"));
    assertNull(errors.userVisibleInternal);
    assertEquals(expected, errors.userVisible);
  }

  LoggedErrors logException(Exception e) {
    LoggedErrors result = new LoggedErrors();
    new ErrorLogger(
            new ErrorLogger.LogImpl() {
              @Override
              public void logUserVisible(String message) {
                assertNull(result.userVisible);
                result.userVisible = message;
              }

              @Override
              public void logUserVisibleInternalError(String message) {
                assertNull(result.userVisibleInternal);
                result.userVisibleInternal = message;
              }

              @Override
              public void logVerbose(Throwable e) {
                assertNull(result.verbose);
                result.verbose = e;
              }
            },
            new HumanReadableExceptionAugmentor(
                ImmutableMap.of(
                    Pattern.compile("main.cpp:1:13: error: expected ('}')"), "Try adding $1!")))
        .setSuppressStackTraces(true)
        .logException(e);
    assertTrue(result.userVisibleInternal == null ^ result.userVisible == null);
    assertNotNull(result.verbose);
    assertEquals(e, result.verbose);
    return result;
  }
}
