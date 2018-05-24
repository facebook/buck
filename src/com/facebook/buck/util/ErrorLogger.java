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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.handler.HumanReadableExceptionAugmentor;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.EventDispatcher;
import com.facebook.buck.util.exceptions.ExceptionWithContext;
import com.facebook.buck.util.exceptions.WrapsException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class ErrorLogger {
  private static final com.facebook.buck.log.Logger LOG =
      com.facebook.buck.log.Logger.get(ErrorLogger.class);
  private boolean suppressStackTraces = false;

  public ErrorLogger setSuppressStackTraces(boolean enabled) {
    suppressStackTraces = enabled;
    return this;
  }

  @VisibleForTesting
  public interface LogImpl {

    void logUserVisible(String message);

    void logUserVisibleInternalError(String message);

    void logVerbose(Throwable e);
  }

  private final LogImpl logger;
  private final HumanReadableExceptionAugmentor errorAugmentor;

  /** Prints the stacktrace as formatted by an ErrorLogger. */
  @VisibleForTesting
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

  public ErrorLogger(
      EventDispatcher dispatcher,
      String userPrefix,
      String verboseMessage,
      HumanReadableExceptionAugmentor errorAugmentor) {
    this(
        new LogImpl() {
          @Override
          public void logUserVisible(String message) {
            dispatcher.post(ConsoleEvent.severe(userPrefix + message));
          }

          @Override
          public void logUserVisibleInternalError(String message) {
            // TODO(cjhopman): This should be colored to make it obviously different from a user
            // error.
            dispatcher.post(ConsoleEvent.severe("Buck encountered an internal error\n" + message));
          }

          @Override
          public void logVerbose(Throwable e) {
            LOG.debug(e, verboseMessage);
          }
        },
        errorAugmentor);
  }

  public ErrorLogger(LogImpl logger, HumanReadableExceptionAugmentor errorAugmentor) {
    this.logger = logger;
    this.errorAugmentor = errorAugmentor;
  }

  public void logException(Throwable e) {
    logger.logVerbose(e);
    Throwable parent = null;

    // TODO(cjhopman): Think about how to handle multiline context strings.
    List<String> context = new LinkedList<>();
    while (e instanceof ExecutionException
        || e instanceof UncheckedExecutionException
        || e instanceof WrapsException) {
      if (e instanceof ExceptionWithContext) {
        ((ExceptionWithContext) e).getContext().ifPresent(msg -> context.add(0, msg));
      }
      Throwable cause = e.getCause();
      if (!(cause instanceof Exception)) {
        break;
      }
      // TODO(cjhopman): Should parent point to the closest parent with context instead of just the
      // parent? If the parent doesn't include context, we're currently removing parts of the stack
      // trace without any context to replace it.
      parent = e;
      e = cause;
    }

    logUserVisible(e, parent, context);
  }

  private void logUserVisible(
      Throwable rootCause, @Nullable Throwable parent, List<String> context) {
    StringBuilder messageBuilder = new StringBuilder();
    // TODO(cjhopman): Based on verbosity, get the stacktrace here instead of just the message.
    messageBuilder.append(getMessageForRootCause(rootCause, parent));
    if (!context.isEmpty()) {
      messageBuilder.append("\n");
      messageBuilder.append(
          Joiner.on("\n").join(context.stream().map(c -> "    " + c).collect(Collectors.toList())));
    }

    if (rootCause instanceof HumanReadableException) {
      logger.logUserVisible(errorAugmentor.getAugmentedError(messageBuilder.toString()));
    } else {
      logger.logUserVisibleInternalError(messageBuilder.toString());
    }
  }

  private String getMessageForRootCause(Throwable rootCause, @Nullable Throwable parent) {
    if (rootCause instanceof HumanReadableException) {
      return ((HumanReadableException) rootCause).getHumanReadableErrorMessage();
    } else if (suppressStackTraces) {
      return String.format("%s: %s", rootCause.getClass().getName(), rootCause.getMessage());
    } else if (parent == null) {
      return Throwables.getStackTraceAsString(rootCause);
    } else {
      Preconditions.checkState(parent.getCause() == rootCause);
      return getStackTraceOfCause(parent);
    }
  }

  private String getStackTraceOfCause(Throwable parent) {
    // If there's a parent, print the parent's stack trace and then filter out it and its
    // suppressed exceptions. This allows us to elide stack frames that are shared between the
    // root cause and its parent.
    return Pattern.compile(".*?\nCaused by: ", Pattern.DOTALL)
        .matcher(Throwables.getStackTraceAsString(parent))
        .replaceFirst("");
  }
}
