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

package com.facebook.buck.util.collect;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.RunnerException;

@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TwoArraysImmutableHashMapBenchmark {

  private final ImmutableList<String> keys10 =
      IntStream.range(0, 10).mapToObj(Integer::toString).collect(ImmutableList.toImmutableList());

  private final ImmutableMap<String, String> immutableMap10 =
      keys10.stream().collect(ImmutableMap.toImmutableMap(i -> i, i -> i + "a"));

  private final TwoArraysImmutableHashMap<String, String> twoArraysMap10 =
      TwoArraysImmutableHashMap.copyOf(immutableMap10);

  private void runIteration(Blackhole bh, Map<String, String> map) {
    bh.consume(map);
    for (Map.Entry<String, String> e : map.entrySet()) {
      bh.consume(e.getKey());
      bh.consume(e.getValue());
    }
  }

  private void runLookup(Blackhole bh, Map<String, String> map) {
    bh.consume(keys10);
    bh.consume(map);
    for (String key : keys10) {
      bh.consume(map.get(key));
    }
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public void iterationGuava(Blackhole bh) {
    runIteration(bh, immutableMap10);
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public void iterationTwoArrays(Blackhole bh) {
    runIteration(bh, twoArraysMap10);
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public void lookupGuava(Blackhole bh) {
    runLookup(bh, immutableMap10);
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public void lookupTwoArrays(Blackhole bh) {
    runLookup(bh, twoArraysMap10);
  }

  public static void main(String[] args) throws IOException, RunnerException {
    Main.main(new String[] {TwoArraysImmutableHashMapBenchmark.class.getName()});
  }
}
