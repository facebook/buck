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

package com.facebook.buck.util;

import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static org.junit.Assert.*;

import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.ExceptionWithContext;
import com.facebook.buck.core.exceptions.ExceptionWithHumanReadableMessage;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.exceptions.WrapsException;
import com.facebook.buck.util.ErrorLogger.DeconstructedException;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileSystemLoopException;
import java.util.Optional;
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
    assertThat(
        errors.userVisibleInternal.replace("\r\n", "\n"),
        Matchers.startsWith("java.lang.RuntimeException: message\n\tat "));
  }

  @Test
  public void testHumanReadableException() {
    LoggedErrors errors = logException(new HumanReadableException("message"));
    assertNull(errors.userVisibleInternal);
    assertEquals("message", errors.userVisible);
  }

  @Test
  public void humanReadableExceptionWithDepStack() {
    LoggedErrors errors =
        logException(new HumanReadableException(DependencyStack.top("//foo:bar"), "message"));
    assertNull(errors.userVisibleInternal);
    assertEquals(linesToText("message", "    At //foo:bar"), errors.userVisible);
  }

  @Test
  public void humanReadableExceptionWithDepStack2() {
    LoggedErrors errors =
        logException(
            new HumanReadableException(
                DependencyStack.top("//foo:bin").child("//bar:lib"), "message"));
    assertNull(errors.userVisibleInternal);
    assertEquals(
        linesToText("message", "    At //bar:lib", "    At //foo:bin"), errors.userVisible);
  }

  private static class InternalExceptionWithDepStack extends RuntimeException
      implements ExceptionWithHumanReadableMessage {

    private final DependencyStack dependencyStack;

    public InternalExceptionWithDepStack(DependencyStack dependencyStack, String message) {
      super(message);

      this.dependencyStack = dependencyStack;
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getMessage();
    }

    @Override
    public DependencyStack getDependencyStack() {
      return dependencyStack;
    }
  }

  @Test
  public void internalErrorWithDepStack() {
    LoggedErrors errors =
        logException(
            new RuntimeException(
                new InternalExceptionWithDepStack(
                    DependencyStack.top("//foo:bin").child("//bar:lib"), "message")));
    // Note that even though ExceptionWithHumanReadableMessage is meant to be
    // a human-readable exception, it is treated as internal error.
    assertThat(
        errors.userVisibleInternal,
        Matchers.allOf(
            Matchers.containsString("InternalExceptionWithDepStack: message"),
            Matchers.containsString("    At //bar:lib"),
            Matchers.containsString("    At //foo:bin")));
  }

  private static class TestException extends Exception
      implements ExceptionWithContext, WrapsException {
    private @Nullable final String context;

    public TestException(Throwable cause) {
      this(cause, null);
    }

    public TestException(Throwable cause, @Nullable String context) {
      super(cause);
      this.context = context;
    }

    @Override
    public Optional<String> getContext() {
      return Optional.ofNullable(context);
    }
  }

  @Test
  public void testWrappedException() {
    LoggedErrors errors = logException(new TestException(new HumanReadableException("message")));
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
        logException(new TestException(new HumanReadableException("message"), "context"));
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
        logException(new TestException(new HumanReadableException(rawMessage), "context"));
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
    LoggedErrors errors =
        logException(new TestException(new RuntimeException(rawMessage), "context"));
    assertNull(errors.userVisible);
    assertThat(
        errors.userVisibleInternal,
        Matchers.containsString("java.lang.RuntimeException: " + rawMessage));
    assertThat(
        errors.userVisibleInternal,
        Matchers.containsString(linesToText("    context", "Try adding '}'!")));
  }

  @Test
  public void testInterruptedException() {
    LoggedErrors errors =
        logException(new TestException(new InterruptedException("This has been interrupted.")));

    assertEquals("Interrupted", errors.userVisible);
    assertNull(errors.userVisibleInternal);
  }

  @Test
  public void testClosedByInterruptedException() {
    LoggedErrors errors = logException(new TestException(new ClosedByInterruptException()));

    assertEquals("Interrupted", errors.userVisible);
    assertNull(errors.userVisibleInternal);
  }

  @Test
  public void testOutOfMemoryError() {
    LoggedErrors errors = logException(new TestException(new OutOfMemoryError("No more memory!")));

    assertNull(errors.userVisible);
    assertThat(
        errors.userVisibleInternal,
        Matchers.containsString(
            "Buck ran out of memory, you may consider increasing heap size with java args "
                + "(see https://buck.build/files-and-dirs/buckjavaargs.html)"));
    assertThat(
        errors.userVisibleInternal,
        Matchers.containsString("java.lang.OutOfMemoryError: No more memory!"));
  }

  @Test
  public void testFileSystemLoopException() {
    LoggedErrors errors =
        logException(new TestException(new FileSystemLoopException("It's a loop!")));

    assertNull(errors.userVisible);
    assertThat(
        errors.userVisibleInternal,
        Matchers.containsString(
            "Loop detected in your directory, which may be caused by circular symlink. "
                + "You may consider running the command in a smaller directory."));
    assertThat(
        errors.userVisibleInternal,
        Matchers.containsString("java.nio.file.FileSystemLoopException: It's a loop!"));
  }

  @Test
  public void testNoSpaceLeftOnDevice() {
    LoggedErrors errors =
        logException(new TestException(new IOException("No space left on device xyzzy.")));

    assertEquals("No space left on device xyzzy.", errors.userVisible);
    assertNull(errors.userVisibleInternal);
  }

  @Test
  public void handlesIOExceptionWithNoMessage() {
    LoggedErrors errors = logException(new BuckUncheckedExecutionException(new IOException()));
    assertNull(errors.userVisible);
  }

  @Test
  public void testBuckIsDying() {
    LoggedErrors errors =
        logException(new TestException(new BuckIsDyingException("It's all falling apart.")));

    assertNull(errors.userVisible);
    assertEquals("Failed because buck was already dying", errors.userVisibleInternal);
  }

  @Test
  public void testCommandLineException() {
    LoggedErrors errors =
        logException(new TestException(new CommandLineException("--foo isn't an argument, silly")));

    assertEquals("BAD ARGUMENTS: --foo isn't an argument, silly", errors.userVisible);
    assertNull(errors.userVisibleInternal);
  }

  @Test
  public void testDeconstruct() {
    DeconstructedException deconstructed =
        ErrorLogger.deconstruct(
            new TestException(
                new BuckUncheckedExecutionException(
                    new ExecutionException(new IOException("okay")) {}, "a little more context"),
                "a little context"));

    assertThat(
        deconstructed.getAugmentedErrorWithContext(
            "    ", new HumanReadableExceptionAugmentor(ImmutableMap.of())),
        Matchers.allOf(
            Matchers.containsString("java.io.IOException: okay"),
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
        .logException(e);
    assertTrue(result.userVisibleInternal == null ^ result.userVisible == null);
    assertNotNull(result.verbose);
    assertEquals(e, result.verbose);
    return result;
  }

  private static void foo() {
    throw new RuntimeException("FOO");
  }

  private static void bar() {
    try {
      foo();
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(e, "BAR");
    }
  }

  private Exception makeException() {
    try {
      bar();
      throw new AssertionError();
    } catch (Exception e) {
      return e;
    }
  }

  @Test
  public void fullExceptionStackTraceIsPrinted() {
    LoggedErrors errors = logException(makeException());
    assertNull(errors.userVisible);
    assertNotNull(errors.userVisibleInternal);

    // assert it is a full stack trace with messages and method names
    assertThat(errors.userVisibleInternal, Matchers.containsString("FOO"));
    assertThat(errors.userVisibleInternal, Matchers.containsString(".foo"));
    assertThat(errors.userVisibleInternal, Matchers.containsString("BAR"));
    assertThat(errors.userVisibleInternal, Matchers.containsString(".bar"));
  }
}
