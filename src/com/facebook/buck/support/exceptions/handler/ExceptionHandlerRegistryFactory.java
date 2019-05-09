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

package com.facebook.buck.support.exceptions.handler;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.util.BuckIsDyingException;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileSystemLoopException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Util class for creating an {@link ExceptionHandlerRegistry} with the default defaultHandlers */
public class ExceptionHandlerRegistryFactory {

  private static final Map<
          Class<? extends Throwable>, ExceptionHandler<? extends Throwable, ExitCode>>
      defaultHandlers = new LinkedHashMap<>();

  static {
    List<ExceptionHandler<? extends Throwable, ExitCode>> defaulthHandlers =
        Arrays.asList(
            new ExceptionHandler<InterruptedException, ExitCode>(InterruptedException.class) {
              @Override
              public ExitCode handleException(InterruptedException e) {
                return ExitCode.SIGNAL_INTERRUPT;
              }
            },
            new ExceptionHandler<ClosedByInterruptException, ExitCode>(
                ClosedByInterruptException.class) {
              @Override
              public ExitCode handleException(ClosedByInterruptException e) {
                return ExitCode.SIGNAL_INTERRUPT;
              }
            },
            new ExceptionHandler<IOException, ExitCode>(IOException.class) {
              @Override
              public ExitCode handleException(IOException e) {
                if (e instanceof FileSystemLoopException) {
                  return ExitCode.FATAL_GENERIC;
                } else if (e.getMessage() != null
                    && e.getMessage().startsWith("No space left on device")) {
                  return ExitCode.FATAL_DISK_FULL;
                } else {
                  return ExitCode.FATAL_IO;
                }
              }
            },
            new ExceptionHandler<OutOfMemoryError, ExitCode>(OutOfMemoryError.class) {
              @Override
              public ExitCode handleException(OutOfMemoryError e) {
                return ExitCode.FATAL_OOM;
              }
            },
            new ExceptionHandler<BuildFileParseException, ExitCode>(BuildFileParseException.class) {
              @Override
              public ExitCode handleException(BuildFileParseException e) {
                return ExitCode.PARSE_ERROR;
              }
            },
            new ExceptionHandler<CommandLineException, ExitCode>(CommandLineException.class) {
              @Override
              public ExitCode handleException(CommandLineException e) {
                return ExitCode.COMMANDLINE_ERROR;
              }
            },
            new ExceptionHandler<HumanReadableException, ExitCode>(HumanReadableException.class) {
              @Override
              public ExitCode handleException(HumanReadableException e) {
                return ExitCode.BUILD_ERROR;
              }
            },
            new ExceptionHandler<BuckIsDyingException, ExitCode>(BuckIsDyingException.class) {
              @Override
              public ExitCode handleException(BuckIsDyingException e) {
                return ExitCode.FATAL_GENERIC;
              }
            });

    defaulthHandlers.forEach(handler -> defaultHandlers.put(handler.getExceptionType(), handler));
  }

  /**
   * Overriding is defined such that the order of the existing handlers are unaltered. When handlers
   * handle the same exception, the new one will replace the existing handler. New handlers not
   * present in the mapping will be appended at the end of the handling chain.
   *
   * @param exceptionHandlers the defaultHandlers to override some default handler
   * @return new {@link ExceptionHandlerRegistry} with the default defaultHandlers overridden by the
   *     given ones
   */
  @SafeVarargs
  public static ExceptionHandlerRegistry<ExitCode> create(
      ExceptionHandler<? extends Throwable, ExitCode>... exceptionHandlers) {
    LinkedHashMap<Class<? extends Throwable>, ExceptionHandler<? extends Throwable, ExitCode>>
        currentHandlers = new LinkedHashMap<>(defaultHandlers);
    for (int i = 0; i < exceptionHandlers.length; i++) {
      if (currentHandlers.replace(exceptionHandlers[i].getExceptionType(), exceptionHandlers[i])
          == null) {
        currentHandlers.put(exceptionHandlers[i].getExceptionType(), exceptionHandlers[i]);
      }
    }
    return new ExceptionHandlerRegistry<>(
        ImmutableList.copyOf(currentHandlers.values()),
        new ExceptionHandler<Throwable, ExitCode>(Throwable.class) {
          @Override
          public ExitCode handleException(Throwable t) {
            return ExitCode.FATAL_GENERIC;
          }
        });
  }

  /** @return a new {@link ExceptionHandlerRegistry} with the default handlers */
  public static ExceptionHandlerRegistry<ExitCode> create() {
    return new ExceptionHandlerRegistry<>(
        ImmutableList.copyOf(defaultHandlers.values()),
        new ExceptionHandler<Throwable, ExitCode>(Throwable.class) {
          @Override
          public ExitCode handleException(Throwable t) {
            return ExitCode.FATAL_GENERIC;
          }
        });
  }

  private ExceptionHandlerRegistryFactory() {}
}
