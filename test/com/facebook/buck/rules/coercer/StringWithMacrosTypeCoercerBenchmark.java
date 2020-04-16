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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.macros.UnconfiguredStringWithMacros;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class StringWithMacrosTypeCoercerBenchmark {

  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellNameResolver cellNameResolver =
      TestCellPathResolver.get(filesystem).getCellNameResolver();
  private final ForwardRelativePath basePath = ForwardRelativePath.of("");

  private final StringWithMacrosTypeCoercer coercer = StringWithMacrosTypeCoercer.builder().build();

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public UnconfiguredStringWithMacros coerce() throws Exception {
    return coercer.coerceToUnconfigured(cellNameResolver, filesystem, basePath, "foo/bar/baz");
  }

  public static void main(String[] args) throws IOException {
    Main.main(new String[] {StringWithMacrosTypeCoercerBenchmark.class.getName()});
  }
}
