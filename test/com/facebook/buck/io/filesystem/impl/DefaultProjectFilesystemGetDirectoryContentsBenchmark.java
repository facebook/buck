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

package com.facebook.buck.io.filesystem.impl;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.RunnerException;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class DefaultProjectFilesystemGetDirectoryContentsBenchmark {
  @Param({"0", "1", "10", "100", "1000", "10000"})
  public int totalFileCount;

  private TemporaryPaths temporaryPaths = new TemporaryPaths();
  private DefaultProjectFilesystem fileSystem;
  private DefaultProjectFilesystemView fileSystemView;
  private AbsPath pathToEnumerate;

  @Setup(Level.Trial)
  public void setUpFileSystem() throws Exception {
    temporaryPaths.before();
    fileSystem = TestProjectFilesystems.createProjectFilesystem(temporaryPaths.getRoot());
    fileSystemView = fileSystem.asView();
    pathToEnumerate = fileSystem.getRootPath();

    for (int i = 0; i < totalFileCount; ++i) {
      Path path = fileSystem.getPath(String.format("file_%d", i));
      fileSystem.createNewFile(path);
    }
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public Collection<Path> getDirectoryContents() throws IOException {
    return fileSystem.getDirectoryContents(pathToEnumerate.getPath());
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public Collection<Path> getDirectoryContentsFromView() throws IOException {
    return fileSystemView.getDirectoryContents(pathToEnumerate.getPath());
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public Collection<Path> nioArrayList() throws IOException {
    ArrayList<Path> paths = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(pathToEnumerate.getPath())) {
      for (Path ent : stream) {
        paths.add(ent);
      }
    }
    return paths;
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public void nioWithoutCollection(Blackhole blackhole) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(pathToEnumerate.getPath())) {
      for (Path ent : stream) {
        blackhole.consume(ent);
      }
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    temporaryPaths.after();
  }

  public static void main(String[] args) throws IOException, RunnerException {
    Main.main(args);
  }
}
