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

package com.facebook.buck.cli;

import static com.facebook.buck.util.string.MoreStrings.linesToText;

import com.facebook.buck.core.exceptions.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.exceptions.ThrowableCauseIterable;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.remoteexecution.util.MultiThreadedBlobUploader;
import com.facebook.buck.support.exceptions.handler.ExceptionHandlerRegistryFactory;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ExitCode;
import java.nio.channels.ClosedByInterruptException;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** The exception processor used by normal command in MainRunner. */
class MainExceptionProcessor implements ExceptionProcessor {
  private static final Logger LOG = Logger.get(MainRunner.class);

  private BuildId buildId;
  private Console printConsole;
  private Supplier<HumanReadableExceptionAugmentor> augmentor;

  // If we process a corrupt artifact exception, we want to purge the cache. It may not be safe to
  // do that at the point that we are processing the exception so instead we just record that we
  // encountered it and the information we need later.
  // TODO(cjhopman): This doesn't seem to be the appropriate way to do this, we should instead be
  // detecting this via events on the eventbus.
  @Nullable public String corruptArtifactExceptionDescription;

  public MainExceptionProcessor(
      BuildId buildId, Console printConsole, Supplier<HumanReadableExceptionAugmentor> augmentor) {
    this.buildId = buildId;
    this.printConsole = printConsole;
    this.augmentor = augmentor;
    this.corruptArtifactExceptionDescription = null;
  }

  @Override
  public ExitCode processException(Exception e) {
    return processThrowable(e);
  }

  public ExitCode processThrowable(Throwable t) {
    // Note that this could be running at any time during the build and so we should only do
    // informative things here. Real side effects should probably be deferred to a known,
    // controlled place.
    try {
      ErrorLogger logger =
          new ErrorLogger(
              new ErrorLogger.LogImpl() {
                @Override
                public void logUserVisible(String message) {
                  printConsole.printFailure(message);
                }

                @Override
                public void logUserVisibleInternalError(String message) {
                  printConsole.printFailure(
                      linesToText("Buck encountered an internal error", message));
                }

                @Override
                public void logVerbose(Throwable e) {
                  String message = "Command failed:";
                  if (e instanceof InterruptedException
                      || e instanceof ClosedByInterruptException) {
                    message = "Command was interrupted:";
                  }
                  LOG.warn(e, message);
                }
              },
              augmentor.get());
      logger.logException(t);

      if (MultiThreadedBlobUploader.CorruptArtifactException.isCause(
          ThrowableCauseIterable.of(t))) {
        corruptArtifactExceptionDescription =
            MultiThreadedBlobUploader.CorruptArtifactException.getDescription(
                    ThrowableCauseIterable.of(t))
                .orElse("");
      }
    } catch (Throwable ignored) {
      // In some very rare cases error processing may error itself
      // One example is logger implementation to throw while trying to log the error message
      // Even for this case, we want proper exit code to be emitted, so we print the original
      // exception to stderr as a last resort
      System.err.println(t.getMessage());
      t.printStackTrace();
    }
    return ExceptionHandlerRegistryFactory.create().handleException(t);
  }
}
