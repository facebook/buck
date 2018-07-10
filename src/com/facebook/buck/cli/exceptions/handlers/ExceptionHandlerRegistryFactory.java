/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli.exceptions.handlers;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.handler.ExceptionHandler;
import com.facebook.buck.core.exceptions.handler.ExceptionHandlerRegistry;
import com.facebook.buck.core.exceptions.handler.HumanReadableExceptionAugmentor;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.BuckIsDyingException;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ErrorLogger.LogImpl;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.InterruptionFailedException;
import com.google.common.collect.ImmutableList;
import com.martiansoftware.nailgun.NGContext;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileSystemLoopException;
import java.util.Arrays;
import java.util.Optional;

/** Util class for creating an {@link ExceptionHandlerRegistry} with the default handlers */
public class ExceptionHandlerRegistryFactory {

  private static final Logger LOG = Logger.get(ExceptionHandlerRegistryFactory.class);

  /**
   * @param console Console for the related handlers print the messages to
   * @param ngContext NailGun context for the related handlers to take action on
   * @param errorAugmentor Augmentor to make errors more clear to users (e.g. for macro failures)
   * @return a new ExceptionHandlerRegistry with the default handlers
   */
  public static ExceptionHandlerRegistry<ExitCode> create(
      Console console,
      Optional<NGContext> ngContext,
      HumanReadableExceptionAugmentor errorAugmentor) {
    ImmutableList.Builder<ExceptionHandler<? extends Throwable, ExitCode>> handlerListBuilder =
        ImmutableList.builder();

    ErrorLogger logger = createErrorLogger(console, errorAugmentor);

    handlerListBuilder.addAll(
        Arrays.asList(
            new ExceptionHandler<InterruptedException, ExitCode>(InterruptedException.class) {
              @Override
              public ExitCode handleException(InterruptedException e) {
                LOG.info(e, "Execution of the command was interrupted (SIGINT)");
                return ExitCode.SIGNAL_INTERRUPT;
              }
            },
            new ExceptionHandler<ClosedByInterruptException, ExitCode>(
                ClosedByInterruptException.class) {
              @Override
              public ExitCode handleException(ClosedByInterruptException e) {
                LOG.info(e, "Execution of the command was interrupted (SIGINT)");
                return ExitCode.SIGNAL_INTERRUPT;
              }
            },
            new ExceptionHandler<IOException, ExitCode>(IOException.class) {
              @Override
              public ExitCode handleException(IOException e) {
                logger.logException(e);
                if (e instanceof FileSystemLoopException) {
                  return ExitCode.FATAL_GENERIC;
                } else if (e.getMessage().startsWith("No space left on device")) {
                  return ExitCode.FATAL_DISK_FULL;
                } else {
                  return ExitCode.FATAL_IO;
                }
              }
            },
            new ExceptionHandler<OutOfMemoryError, ExitCode>(OutOfMemoryError.class) {
              @Override
              public ExitCode handleException(OutOfMemoryError e) {
                console.printFailureWithStacktrace(
                    e,
                    "Buck ran out of memory, you may consider increasing heap size with java args");
                return ExitCode.FATAL_OOM;
              }
            },
            new ExceptionHandler<BuildFileParseException, ExitCode>(BuildFileParseException.class) {
              @Override
              public ExitCode handleException(BuildFileParseException e) {
                console.printFailure(
                    errorAugmentor.getAugmentedError(e.getHumanReadableErrorMessage()));
                return ExitCode.PARSE_ERROR;
              }
            },
            new ExceptionHandler<CommandLineException, ExitCode>(CommandLineException.class) {
              @Override
              public ExitCode handleException(CommandLineException e) {
                console.printFailure(e, "BAD ARGUMENTS: " + e.getHumanReadableErrorMessage());
                return ExitCode.COMMANDLINE_ERROR;
              }
            },
            new ExceptionHandler<HumanReadableException, ExitCode>(HumanReadableException.class) {
              @Override
              public ExitCode handleException(HumanReadableException e) {
                console.printFailure(
                    errorAugmentor.getAugmentedError(e.getHumanReadableErrorMessage()));
                return ExitCode.BUILD_ERROR;
              }
            },
            new ExceptionHandler<InterruptionFailedException, ExitCode>(
                InterruptionFailedException.class) {
              @Override
              public ExitCode handleException(InterruptionFailedException e) {
                ngContext.ifPresent(c -> c.getNGServer().shutdown(false));
                return ExitCode.SIGNAL_INTERRUPT;
              }
            },
            new ExceptionHandler<BuckIsDyingException, ExitCode>(BuckIsDyingException.class) {
              @Override
              public ExitCode handleException(BuckIsDyingException e) {
                console.printFailure(e, "Fallout because buck was already dying");
                return ExitCode.FATAL_GENERIC;
              }
            }));
    return new ExceptionHandlerRegistry<>(
        handlerListBuilder.build(),
        new ExceptionHandler<Throwable, ExitCode>(Throwable.class) {
          @Override
          public ExitCode handleException(Throwable t) {
            console.printFailureWithStacktrace(t, "UNKNOWN ERROR: " + t.getMessage());
            return ExitCode.FATAL_GENERIC;
          }
        });
  }

  private static ErrorLogger createErrorLogger(
      Console console, HumanReadableExceptionAugmentor augmentor) {
    return new ErrorLogger(
        new LogImpl() {
          @Override
          public void logUserVisible(String message) {
            console.printFailure(message);
          }

          @Override
          public void logUserVisibleInternalError(String message) {
            console.printFailure(message);
          }

          @Override
          public void logVerbose(Throwable e) {
            LOG.warn(e, "Command failed:");
          }
        },
        augmentor);
  }
}
