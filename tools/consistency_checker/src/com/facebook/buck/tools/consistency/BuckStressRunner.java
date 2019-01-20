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

package com.facebook.buck.tools.consistency;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** A class to run a buck command multiple times in parallel, and up to N times */
public class BuckStressRunner {
  /** Indicates that there was a problem while running the stress run */
  static class StressorException extends Exception {
    StressorException(Throwable e, String formatString, Object... formatArgs) {
      super(String.format(formatString, formatArgs), e);
    }

    StressorException(String formatString, Object... formatArgs) {
      super(String.format(formatString, formatArgs));
    }
  }

  /**
   * Runs {@code parallelism} instances of the {@code runners} in parallel. If one fails, no more
   * are scheduled, though inflight runs are not killed. The stdout for each command is written to a
   * file, and stderr is written to {@link System.err}.
   *
   * @param runners The commands to run
   * @param outputDirectory The directory to write stdout files to
   * @param parallelism The number of runs to do at a time
   * @return The paths to each of the output files
   * @throws StressorException If any of the processes fail or their output cannot be written to
   *     temporary files
   */
  public List<Path> run(List<BuckRunner> runners, Path outputDirectory, int parallelism)
      throws StressorException {
    ExecutorService service = Executors.newFixedThreadPool(parallelism);
    ExecutorCompletionService<Integer> completionService = new ExecutorCompletionService<>(service);
    List<Path> filePaths =
        IntStream.range(0, runners.size())
            .mapToObj(i -> outputDirectory.resolve(Integer.toString(i) + ".log"))
            .collect(Collectors.toList());

    List<Future<Integer>> futures = queueRuns(completionService, filePaths, runners);
    String errorMessages = waitForFutures(completionService, futures);
    if (!errorMessages.isEmpty()) {
      throw new StressorException(errorMessages);
    }
    return filePaths;
  }

  private List<Future<Integer>> queueRuns(
      CompletionService<Integer> completionService,
      List<Path> filePaths,
      List<BuckRunner> runners) {
    return IntStream.range(0, runners.size())
        .mapToObj(
            i ->
                completionService.submit(
                    () -> {
                      try (OutputStream writer =
                          Files.newOutputStream(filePaths.get(i), StandardOpenOption.CREATE_NEW)) {
                        int returnCode = runners.get(i).run(writer);
                        if (returnCode != 0) {
                          throw new RuntimeException(
                              new StressorException(
                                  "Got non zero output from buck iteration %s: %s", i, returnCode));
                        }
                      } catch (InterruptedException | IOException e) {
                        throw new RuntimeException(
                            new StressorException(
                                e,
                                "Got an error while running buck iteration %s: %s",
                                i,
                                e.getMessage()));
                      }
                    },
                    0))
        .collect(Collectors.toList());
  }

  private String waitForFutures(
      CompletionService<Integer> completionService, List<Future<Integer>> futures) {
    for (int i = 0; i < futures.size(); i++) {
      try {
        int returnCode = completionService.take().get();
      } catch (InterruptedException | ExecutionException e) {
        // On first failure, try to cancel any remaining futures so we don't spawn extra
        // processes
        for (Future future : futures) {
          future.cancel(false);
        }
        break;
      }
    }
    return getCumulativeErrorMessages(futures);
  }

  private String getCumulativeErrorMessages(List<Future<Integer>> futures) {
    return IntStream.range(0, futures.size())
        .mapToObj(
            i -> {
              try {
                futures.get(i).get();
                return Optional.<String>empty();
              } catch (CancellationException e) {
                return Optional.of(
                    String.format("Buck run %s was canceled due to a previous error", i));
              } catch (InterruptedException e) {
                return Optional.of(e.getMessage());
              } catch (ExecutionException e) {
                // We only throw RuntimeException from these lambdas,
                // so go ExecutionException -> RuntimeException -> real exception
                return Optional.of(e.getCause().getCause().getMessage());
              }
            })
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.joining(System.lineSeparator()));
  }
}
