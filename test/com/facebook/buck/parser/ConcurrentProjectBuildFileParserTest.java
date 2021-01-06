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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.ObjectArrays;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ConcurrentProjectBuildFileParserTest {

  private static class MonitoringProjectBuildFileParser implements ProjectBuildFileParser {

    private int calls = 0;
    private final Semaphore semaphore = new Semaphore(1);
    private final AtomicInteger totalCalls;

    private MonitoringProjectBuildFileParser(AtomicInteger totalCalls) {
      this.totalCalls = totalCalls;
    }

    public int getCalls() {
      return calls;
    }

    private void processCall(String method) {
      if (!semaphore.tryAcquire()) {
        throw new RuntimeException(method + " got unsynchronized entry");
      }
      calls++;
      semaphore.release();
    }

    @Override
    @SuppressWarnings("unused")
    public BuildFileManifest getManifest(Path buildFile) {
      processCall("getManifest");
      return null;
    }

    @Override
    public void reportProfile() {
      totalCalls.addAndGet(calls);
    }

    @Override
    @SuppressWarnings("unused")
    public ImmutableSortedSet<String> getIncludedFiles(Path buildFile) {
      processCall("getIncludedFiles");
      return null;
    }

    @Override
    @SuppressWarnings("unused")
    public boolean globResultsMatchCurrentState(
        Path buildFile, ImmutableList<GlobSpecWithResult> existingGlobsWithResults) {
      processCall("globResultsMatchCurrentState");
      return false;
    }

    @Override
    public void close() {}
  }

  /**
   * Abstract do-nothing {@link ProjectBuildFileParser} that can be simply extended to implement a
   * specific mock
   */
  private abstract static class WaitingProjectBuildFileParser implements ProjectBuildFileParser {

    private CountDownLatch latch;
    private AtomicInteger closeCallCounter;

    public WaitingProjectBuildFileParser(CountDownLatch latch, AtomicInteger closeCallCounter) {
      this.latch = latch;
      this.closeCallCounter = closeCallCounter;
    }

    private void waitLatch() {
      latch.countDown();
      try {
        // block the function until it is called on desired number of threads
        // this ensures that ConcurrentProjectBuildFileParser lazily creates required number
        // of objects
        latch.await();
      } catch (InterruptedException iex) {
        throw new RuntimeException(iex);
      }
    }

    @Override
    @SuppressWarnings("unused")
    public BuildFileManifest getManifest(Path buildFile) {
      waitLatch();
      return null;
    }

    @Override
    public void reportProfile() {}

    @Override
    @SuppressWarnings("unused")
    public ImmutableSortedSet<String> getIncludedFiles(Path buildFile) {
      waitLatch();
      return null;
    }

    @Override
    @SuppressWarnings("unused")
    public boolean globResultsMatchCurrentState(
        Path buildFile, ImmutableList<GlobSpecWithResult> existingGlobsWithResults) {
      waitLatch();
      return false;
    }

    @Override
    public void close() throws BuildFileParseException, InterruptedException, IOException {
      closeCallCounter.incrementAndGet();
    }
  }

  @Test
  public void parsingIsConcurrent() throws Exception {

    final int ITERATIONS = 100000;
    AtomicInteger totalCalls = new AtomicInteger(0);

    try (ConcurrentProjectBuildFileParser buildFileParser =
        new ConcurrentProjectBuildFileParser(
            () -> new MonitoringProjectBuildFileParser(totalCalls))) {
      ExecutorService fixedThreadExecutor = Executors.newFixedThreadPool(10);
      ListeningExecutorService executorService =
          MoreExecutors.listeningDecorator(fixedThreadExecutor);

      List<ListenableFuture<?>> futures = new ArrayList<>(ITERATIONS * 3);

      for (int i = 0; i < ITERATIONS; i++) {
        futures.add(executorService.submit(() -> buildFileParser.getManifest(Paths.get(""))));
        futures.add(executorService.submit(() -> buildFileParser.getIncludedFiles(Paths.get(""))));
        futures.add(
            executorService.submit(
                () ->
                    buildFileParser.globResultsMatchCurrentState(
                        Paths.get(""), ImmutableList.of())));
      }

      Futures.allAsList(futures).get();
      fixedThreadExecutor.shutdownNow();

      // this should sum all calls count from all created workers into totalCalls
      buildFileParser.reportProfile();

      assertEquals(ITERATIONS * 3, totalCalls.get());
    }
  }

  private WaitingProjectBuildFileParser getParserDoesntThrow(
      CountDownLatch latch, AtomicInteger closeCallCounter) {
    return new WaitingProjectBuildFileParser(latch, closeCallCounter) {};
  }

  private WaitingProjectBuildFileParser getParserThrowsParseException(
      CountDownLatch latch, AtomicInteger closeCallCounter) {
    return new WaitingProjectBuildFileParser(latch, closeCallCounter) {
      @Override
      public void close() throws BuildFileParseException, InterruptedException, IOException {
        super.close();
        throw BuildFileParseException.createForUnknownParseError("BuildFileParseException");
      }
    };
  }

  private WaitingProjectBuildFileParser getParserThrowsUncheckedException(
      CountDownLatch latch, AtomicInteger closeCallCounter) {
    return new WaitingProjectBuildFileParser(latch, closeCallCounter) {
      @Override
      public void close() throws BuildFileParseException, InterruptedException, IOException {
        super.close();
        throw new RuntimeException("RuntimeException");
      }
    };
  }

  private WaitingProjectBuildFileParser getParserThrowsIOException(
      CountDownLatch latch, AtomicInteger closeCallCounter) {
    return new WaitingProjectBuildFileParser(latch, closeCallCounter) {
      @Override
      public void close() throws BuildFileParseException, InterruptedException, IOException {
        super.close();
        throw new IOException("IOException");
      }
    };
  }

  private void executeAndCloseAndValidate(
      List<BiFunction<CountDownLatch, AtomicInteger, ProjectBuildFileParser>> parsers,
      Class<?>... expectedExceptionTypes) {
    int threads = parsers.size();
    CountDownLatch latch = new CountDownLatch(threads);
    AtomicInteger closeCallCounter = new AtomicInteger(0);

    AtomicInteger counter = new AtomicInteger(0);
    Supplier<ProjectBuildFileParser> factory =
        () -> {
          int order = counter.getAndIncrement();
          return parsers.get(order).apply(latch, closeCallCounter);
        };

    List<ListenableFuture<?>> futures = new ArrayList<>(threads);

    try {
      try (ConcurrentProjectBuildFileParser buildFileParser =
          new ConcurrentProjectBuildFileParser(factory)) {
        ExecutorService fixedThreadExecutor = Executors.newFixedThreadPool(threads);
        ListeningExecutorService executorService =
            MoreExecutors.listeningDecorator(fixedThreadExecutor);
        for (int i = 0; i < threads; i++) {
          futures.add(executorService.submit(() -> buildFileParser.getManifest(Paths.get(""))));
        }
        Futures.allAsList(futures).get();
      }
    } catch (Throwable t) {
      // try-with-resources block should try to close all parsers and rethrow an exception, if any

      // all close() methods should be called
      assertEquals(threads, closeCallCounter.get());

      Object[] allExceptionTypes =
          ObjectArrays.concat(
              t.getClass(), Arrays.stream(t.getSuppressed()).map(s -> s.getClass()).toArray());

      // one of the exceptions of expected type should be rethrown, others should be in suppressed
      // there is no requirement on the order
      assertThat(expectedExceptionTypes, Matchers.arrayContainingInAnyOrder(allExceptionTypes));

      return;
    }

    if (expectedExceptionTypes.length > 0) {
      fail("Exception should be thrown");
    }
  }

  @Test
  public void closeAllParsersWhenNoneThrow() {
    List<BiFunction<CountDownLatch, AtomicInteger, ProjectBuildFileParser>> parsers =
        Lists.newArrayList(
            (latch, counter) -> getParserDoesntThrow(latch, counter),
            (latch, counter) -> getParserDoesntThrow(latch, counter));

    executeAndCloseAndValidate(parsers);
  }

  @Test
  public void close3ParsersWhen1stThrows() {
    List<BiFunction<CountDownLatch, AtomicInteger, ProjectBuildFileParser>> parsers =
        Lists.newArrayList(
            (latch, counter) -> getParserThrowsParseException(latch, counter),
            (latch, counter) -> getParserDoesntThrow(latch, counter),
            (latch, counter) -> getParserDoesntThrow(latch, counter));

    executeAndCloseAndValidate(parsers, BuildFileParseException.class);
  }

  @Test
  public void close3ParsersWhen2ndThrows() {
    List<BiFunction<CountDownLatch, AtomicInteger, ProjectBuildFileParser>> parsers =
        Lists.newArrayList(
            (latch, counter) -> getParserDoesntThrow(latch, counter),
            (latch, counter) -> getParserThrowsParseException(latch, counter),
            (latch, counter) -> getParserDoesntThrow(latch, counter));

    executeAndCloseAndValidate(parsers, BuildFileParseException.class);
  }

  @Test
  public void close3ParsersWhen1stAnd3rdThrow() {
    List<BiFunction<CountDownLatch, AtomicInteger, ProjectBuildFileParser>> parsers =
        Lists.newArrayList(
            (latch, counter) -> getParserThrowsParseException(latch, counter),
            (latch, counter) -> getParserDoesntThrow(latch, counter),
            (latch, counter) -> getParserThrowsIOException(latch, counter));

    executeAndCloseAndValidate(parsers, BuildFileParseException.class, IOException.class);
  }

  @Test
  public void close3ParsersWhenUncheckedExceptionIsThrown() {
    List<BiFunction<CountDownLatch, AtomicInteger, ProjectBuildFileParser>> parsers =
        Lists.newArrayList(
            (latch, counter) -> getParserDoesntThrow(latch, counter),
            (latch, counter) -> getParserThrowsUncheckedException(latch, counter),
            (latch, counter) -> getParserDoesntThrow(latch, counter));

    executeAndCloseAndValidate(parsers, RuntimeException.class);
  }

  @Test
  public void close3ParsersWhenAllThrow() {
    List<BiFunction<CountDownLatch, AtomicInteger, ProjectBuildFileParser>> parsers =
        Lists.newArrayList(
            (latch, counter) -> getParserThrowsIOException(latch, counter),
            (latch, counter) -> getParserThrowsUncheckedException(latch, counter),
            (latch, counter) -> getParserThrowsParseException(latch, counter));

    executeAndCloseAndValidate(
        parsers, IOException.class, RuntimeException.class, BuildFileParseException.class);
  }
}
