/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
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
    public BuildFileManifest getBuildFileManifest(Path buildFile) {
      processCall("getBuildFileManifest");
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
        futures.add(
            executorService.submit(() -> buildFileParser.getBuildFileManifest(Paths.get(""))));
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
}
