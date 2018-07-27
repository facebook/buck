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

import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.junit.Assert.*;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.handler.HumanReadableExceptionAugmentor;
import com.facebook.buck.util.ErrorLogger.DeconstructedException;
import com.facebook.buck.util.exceptions.BuckExecutionException;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileSystemLoopException;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
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
    assertEquals(linesToText("message", "    context"), errors.userVisible);
  }

  @Test
  public void addsErrorMessageAugmentations() {
    String rawMessage =
        linesToText(
            "\u001B[1mmain.cpp:1:13: \u001B[0m\u001B[0;1;31merror: \u001B[0m\u001B[1mexpected '}'\u001B[0m",
            "int main() {",
            "\u001B[0;1;32m            ^",
            "\u001B[0m\u001B[1mmain.cpp:1:12: \u001B[0m\u001B[0;1;30mnote: \u001B[0mto match this '{'\u001B[0m",
            "int main() {",
            "\u001B[0;1;32m           ^",
            "\u001B[0m1 error generated.");
    String expected = linesToText(rawMessage, "    context", "Try adding '}'!");

    LoggedErrors errors =
        logException(new BuckExecutionException(new HumanReadableException(rawMessage), "context"));
    assertNull(errors.userVisibleInternal);
    assertEquals(expected, errors.userVisible);
  }

  @Test
  public void addsErrorMessageAugmentationsToInternalErrors() {
    String rawMessage =
        linesToText(
            "\u001B[1mmain.cpp:1:13: \u001B[0m\u001B[0;1;31merror: \u001B[0m\u001B[1mexpected '}'\u001B[0m",
            "int main() {",
            "\u001B[0;1;32m            ^",
            "\u001B[0m\u001B[1mmain.cpp:1:12: \u001B[0m\u001B[0;1;30mnote: \u001B[0mto match this '{'\u001B[0m",
            "int main() {",
            "\u001B[0;1;32m           ^",
            "\u001B[0m1 error generated.");
    String expected =
        linesToText("java.lang.RuntimeException: " + rawMessage, "    context", "Try adding '}'!");

    LoggedErrors errors =
        logException(new BuckExecutionException(new RuntimeException(rawMessage), "context"));
    assertNull(errors.userVisible);
    assertEquals(expected, errors.userVisibleInternal);
  }

  @Test
  public void testInterruptedException() {
    LoggedErrors errors =
        logException(
            new BuckExecutionException(new InterruptedException("This has been interrupted.")));

    assertEquals("Interrupted", errors.userVisible);
    assertNull(errors.userVisibleInternal);
  }

  @Test
  public void testClosedByInterruptedException() {
    LoggedErrors errors =
        logException(new BuckExecutionException(new ClosedByInterruptException()));

    assertEquals("Interrupted", errors.userVisible);
    assertNull(errors.userVisibleInternal);
  }

  @Test
  public void testOutOfMemoryError() {
    LoggedErrors errors =
        logException(new BuckExecutionException(new OutOfMemoryError("No more memory!")));

    assertNull(errors.userVisible);
    assertEquals(
        linesToText(
            "Buck ran out of memory, you may consider increasing heap size with java args "
                + "(see https://buckbuild.com/concept/buckjavaargs.html)",
            "java.lang.OutOfMemoryError: No more memory!"),
        errors.userVisibleInternal);
  }

  @Test
  public void testFileSystemLoopException() {
    LoggedErrors errors =
        logException(new BuckExecutionException(new FileSystemLoopException("It's a loop!")));

    assertNull(errors.userVisible);
    assertEquals(
        linesToText(
            "Loop detected in your directory, which may be caused by circular symlink. "
                + "You may consider running the command in a smaller directory.",
            "java.nio.file.FileSystemLoopException: It's a loop!"),
        errors.userVisibleInternal);
  }

  @Test
  public void testNoSpaceLeftOnDevice() {
    LoggedErrors errors =
        logException(new BuckExecutionException(new IOException("No space left on device xyzzy.")));

    assertEquals("No space left on device xyzzy.", errors.userVisible);
    assertNull(errors.userVisibleInternal);
  }

  @Test
  public void testBuckIsDying() {
    LoggedErrors errors =
        logException(
            new BuckExecutionException(new BuckIsDyingException("It's all falling apart.")));

    assertNull(errors.userVisible);
    assertEquals("Failed because buck was already dying", errors.userVisibleInternal);
  }

  @Test
  public void testCommandLineException() {
    LoggedErrors errors =
        logException(
            new BuckExecutionException(new CommandLineException("--foo isn't an argument, silly")));

    assertEquals("BAD ARGUMENTS: --foo isn't an argument, silly", errors.userVisible);
    assertNull(errors.userVisibleInternal);
  }

  @Test
  public void testDeconstruct() {
    DeconstructedException deconstructed =
        ErrorLogger.deconstruct(
            new BuckExecutionException(
                new BuckUncheckedExecutionException(
                    new ExecutionException(new IOException("okay")) {}, "a little more context"),
                "a little context"));

    assertThat(
        deconstructed.getAugmentedErrorWithContext(
            false, "    ", new HumanReadableExceptionAugmentor(ImmutableMap.of())),
        Matchers.allOf(
            Matchers.startsWith("java.io.IOException: okay"),
            Matchers.containsString("    a little more context"),
            Matchers.containsString("    a little context")));
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
