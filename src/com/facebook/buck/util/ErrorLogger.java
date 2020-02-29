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

import com.facebook.buck.core.exceptions.ExceptionWithContext;
import com.facebook.buck.core.exceptions.ExceptionWithHumanReadableMessage;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.exceptions.WrapsException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileSystemLoopException;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class ErrorLogger {

  @VisibleForTesting
  public interface LogImpl {
    /**
     * For user errors (HumanReadableException and similar), the user-friendly message will be
     * reported through logUserVisible()
     */
    void logUserVisible(String message);

    /**
     * For internal errrors (all non-user errors), the user-friendly message will be reported
     * through logUserVisibleInternalError()
     */
    void logUserVisibleInternalError(String message);

    /** All exceptions will be passed to logVerbose. */
    void logVerbose(Throwable e);
  }

  private final LogImpl logger;
  private final HumanReadableExceptionAugmentor errorAugmentor;

  /** Prints the stacktrace as formatted by an ErrorLogger. */
  public static String getUserFriendlyMessage(Throwable e) {
    StringBuilder builder = new StringBuilder();
    new ErrorLogger(
            new LogImpl() {
              @Override
              public void logUserVisible(String message) {
                builder.append(message);
              }

              @Override
              public void logUserVisibleInternalError(String message) {
                builder.append(message);
              }

              @Override
              public void logVerbose(Throwable e) {}
            },
            new HumanReadableExceptionAugmentor(ImmutableMap.of()))
        .logException(e);
    return builder.toString();
  }

  public ErrorLogger(LogImpl logger, HumanReadableExceptionAugmentor errorAugmentor) {
    this.logger = logger;
    this.errorAugmentor = errorAugmentor;
  }

  /**
   * The result of exception "deconstruction". Provides access to the user-friendly message with
   * context.
   */
  public static class DeconstructedException {
    private final Throwable originalException;
    private final Throwable rootCause;
    private final ImmutableList<String> context;

    private DeconstructedException(
        Throwable originalException, Throwable rootCause, ImmutableList<String> context) {
      this.originalException = originalException;
      this.rootCause = rootCause;
      this.context = context;
    }

    private Optional<String> getContext(String indent) {
      return context.isEmpty()
          ? Optional.empty()
          : Optional.of(
              Joiner.on(System.lineSeparator())
                  .join(context.stream().map(c -> indent + c).collect(Collectors.toList())));
    }

    /** Returns the message (and optionally stack trace) for the root cause. */
    public String getMessage(boolean suppressStackTraces) {
      if (rootCause instanceof HumanReadableException) {
        return ((HumanReadableException) rootCause).getHumanReadableErrorMessage();
      }

      if (rootCause instanceof InterruptedException
          || rootCause instanceof ClosedByInterruptException) {
        return "Interrupted";
      }

      if (rootCause instanceof BuckIsDyingException) {
        return "Failed because buck was already dying";
      }

      if (isNoSpaceOnDevice()) {
        return rootCause.getMessage();
      }

      String message = "";
      if (rootCause instanceof FileSystemLoopException) {
        // TODO(cjhopman): Is this message helpful? What's a smaller directory?
        message =
            "Loop detected in your directory, which may be caused by circular symlink. "
                + "You may consider running the command in a smaller directory."
                + System.lineSeparator();
      }

      if (rootCause instanceof OutOfMemoryError) {
        message =
            "Buck ran out of memory, you may consider increasing heap size with java args "
                + "(see https://buck.build/files-and-dirs/buckjavaargs.html)"
                + System.lineSeparator();
      }

      if (suppressStackTraces) {
        return String.format(
            "%s%s: %s", message, rootCause.getClass().getName(), rootCause.getMessage());
      }

      return String.format("%s%s", message, Throwables.getStackTraceAsString(originalException));
    }

    /** Indicates whether this exception is a user error or a buck internal error. */
    public boolean isUserError() {
      if (rootCause instanceof HumanReadableException
          || rootCause instanceof InterruptedException
          || rootCause instanceof ClosedByInterruptException) {
        return true;
      }

      return isNoSpaceOnDevice();
    }

    public boolean isNoSpaceOnDevice() {
      return rootCause instanceof IOException
          && rootCause.getMessage() != null
          && rootCause.getMessage().startsWith("No space left on device");
    }

    public Throwable getRootCause() {
      return rootCause;
    }

    /**
     * Creates the user-friendly exception with context, masked stack trace (if not suppressed), and
     * with augmentations.
     */
    public String getAugmentedErrorWithContext(
        String indent, HumanReadableExceptionAugmentor errorAugmentor) {
      StringBuilder messageBuilder = new StringBuilder();
      // TODO(cjhopman): Based on verbosity, get the stacktrace here instead of just the message.
      messageBuilder.append(getMessage(false));
      Optional<String> context = getContext(indent);
      if (context.isPresent()) {
        messageBuilder.append(System.lineSeparator());
        messageBuilder.append(context.get());
      }
      return errorAugmentor.getAugmentedError(messageBuilder.toString());
    }
  }

  public void logException(Throwable e) {
    logger.logVerbose(e);
    logUserVisible(deconstruct(e));
  }

  private static ImmutableList<Throwable> causeStack(Throwable e) {
    ImmutableList.Builder<Throwable> stack = ImmutableList.builder();

    stack.add(e);

    while (e != null) {
      e = e.getCause();
      if (e != null) {
        stack.add(e);
      }
    }

    return stack.build();
  }

  /** Deconstructs an exception to assist in creating user-friendly messages. */
  public static DeconstructedException deconstruct(Throwable originalException) {
    Throwable e = originalException;

    // TODO(cjhopman): Think about how to handle multiline context strings.
    List<String> context = new LinkedList<>();

    for (Throwable t : causeStack(e)) {
      if (t instanceof ExceptionWithContext) {
        ((ExceptionWithContext) t).getContext().ifPresent(msg -> context.add(0, msg));
        e = e.getCause();
      }
    }

    for (Throwable t : causeStack(e).reverse()) {
      if (t instanceof ExceptionWithHumanReadableMessage) {
        ImmutableList<String> stack =
            ((ExceptionWithHumanReadableMessage) t)
                .getDependencyStack()
                .collectStringsFilterAdjacentDupes();
        // Stop at deepest exception with non-empty dep stack
        if (!stack.isEmpty()) {
          for (String dep : stack) {
            context.add("At " + dep);
          }
          break;
        }
      }
    }

    while (e instanceof ExecutionException
        || e instanceof UncheckedExecutionException
        || e instanceof WrapsException) {

      if (e.getCause() == null) {
        break;
      }

      // TODO(cjhopman): Should parent point to the closest parent with context instead of just the
      // parent? If the parent doesn't include context, we're currently removing parts of the stack
      // trace without any context to replace it.
      e = e.getCause();
    }

    return new DeconstructedException(
        originalException, Objects.requireNonNull(e), ImmutableList.copyOf(context));
  }

  private void logUserVisible(DeconstructedException deconstructed) {
    String augmentedError = deconstructed.getAugmentedErrorWithContext("    ", errorAugmentor);
    if (deconstructed.isUserError()) {
      logger.logUserVisible(augmentedError);
    } else {
      logger.logUserVisibleInternalError(augmentedError);
    }
  }
}
