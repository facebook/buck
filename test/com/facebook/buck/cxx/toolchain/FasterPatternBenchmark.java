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

package com.facebook.buck.cxx.toolchain;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.RunnerException;

@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FasterPatternBenchmark {
  private String what = ".*/usr/lib/clang/.*";
  private Pattern pattern = Pattern.compile(what, Pattern.DOTALL);
  private FasterPattern fasterPattern = FasterPattern.compile(what);
  private String fileName = "/Users/somebody/repo/project/subproject/module/header.h";

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public boolean pattern() {
    return pattern.matcher(fileName).matches();
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public boolean fasterPattern() {
    return fasterPattern.matches(fileName);
  }

  public static void main(String[] args) throws IOException, RunnerException {
    Main.main(new String[] {FasterPatternBenchmark.class.getName()});
  }
}
