// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.benchmarks;

import com.android.tools.r8.CompilationException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.D8Output;
import com.android.tools.r8.utils.ThreadUtils;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;

public class IncrementalDexingBenchmark {
  private static final int ITERATIONS = 1000;

  public static void compile(ExecutorService executor) throws IOException, CompilationException {
    D8Output output =
        D8.run(
            D8Command.builder()
                .addProgramFiles(Paths.get("build/test/examples/arithmetic.jar"))
                .setMode(CompilationMode.DEBUG)
                .build(),
            executor);
    if (output.getDexResources().size() != 1) {
      throw new RuntimeException("WAT");
    }
  }

  public static void main(String[] args) throws IOException, CompilationException {
    int threads = Integer.min(Runtime.getRuntime().availableProcessors(), 16) / 2;
    ExecutorService executor = ThreadUtils.getExecutorService(threads);
    try {
      long start = System.nanoTime();
      for (int i = 0; i < ITERATIONS; i++) {
        compile(executor);
      }
      double elapsedMs = (System.nanoTime() - start) / 1000000.0;
      BenchmarkUtils.printRuntimeMilliseconds("IncrementalDexing", elapsedMs);
    } finally {
      executor.shutdown();
    }
  }
}
