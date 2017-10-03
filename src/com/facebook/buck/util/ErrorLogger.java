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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.EventDispatcher;
import com.facebook.buck.util.exceptions.ExceptionWithContext;
import com.facebook.buck.util.exceptions.WrapsException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class ErrorLogger {
  private static final com.facebook.buck.log.Logger LOG =
      com.facebook.buck.log.Logger.get(ErrorLogger.class);

  @VisibleForTesting
  public interface LogImpl {
    void logUserVisible(String message);

    void logUserVisibleInternalError(String message);

    void logVerbose(Throwable e);
  }

  private final LogImpl logger;

  public ErrorLogger(EventDispatcher dispatcher, String userPrefix, String verboseMessage) {
    this(
        new LogImpl() {
          @Override
          public void logUserVisible(String message) {
            dispatcher.post(ConsoleEvent.severe(userPrefix + message));
          }

          @Override
          public void logUserVisibleInternalError(String message) {
            // TODO(cjhopman): This should be colored to make it obviously different from a user error.
            dispatcher.post(ConsoleEvent.severe("Buck encountered an internal error\n" + message));
          }

          @Override
          public void logVerbose(Throwable e) {
            LOG.debug(e, verboseMessage);
          }
        });
  }

  public ErrorLogger(LogImpl logger) {
    this.logger = logger;
  }

  public void logException(Throwable e) {
    logger.logVerbose(e);

    // TODO(cjhopman): Think about how to handle multiline context strings.
    List<String> context = new LinkedList<>();
    while (e instanceof ExecutionException || e instanceof WrapsException) {
      if (e instanceof ExceptionWithContext) {
        ((ExceptionWithContext) e).getContext().ifPresent(msg -> context.add(0, msg));
      }
      Throwable cause = e.getCause();
      if (!(cause instanceof Exception)) {
        break;
      }
      e = cause;
    }

    logUserVisible(e, context);
  }

  protected void logUserVisible(Throwable rootCause, List<String> context) {
    StringBuilder messageBuilder = new StringBuilder();
    // TODO(cjhopman): Based on verbosity, get the stacktrace here instead of just the message.
    messageBuilder.append(getMessageForRootCause(rootCause));
    if (!context.isEmpty()) {
      messageBuilder.append("\n");
      messageBuilder.append(
          Joiner.on("\n").join(context.stream().map(c -> "    " + c).collect(Collectors.toList())));
    }

    if (rootCause instanceof HumanReadableException) {
      logger.logUserVisible(messageBuilder.toString());
    } else {
      logger.logUserVisibleInternalError(messageBuilder.toString());
    }
  }

  protected String getMessageForRootCause(Throwable rootCause) {
    if (rootCause instanceof HumanReadableException) {
      return ((HumanReadableException) rootCause).getHumanReadableErrorMessage();
    } else {
      return rootCause.getMessage();
    }
  }
}
