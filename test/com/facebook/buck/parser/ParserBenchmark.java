/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;
import com.google.caliper.Benchmark;
import com.google.caliper.Param;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ParserBenchmark {
  @Param({"10", "100", "500"})
  private int targetCount = 10;

  @Param({"1", "2", "10"})
  private int threadCount = 1;

  private TemporaryPaths tempDir = new TemporaryPaths();

  private Parser parser;
  private ProjectFilesystem filesystem;
  private Cell cell;
  private ListeningExecutorService executorService;

  @Before
  public void setUpTest() throws Exception {
    targetCount = 10;
    setUpBenchmark();
  }

  @BeforeExperiment
  private void setUpBenchmark() throws Exception {
    tempDir.before();
    Path root = tempDir.getRoot();
    Files.createDirectories(root);
    filesystem = TestProjectFilesystems.createProjectFilesystem(root);

    Path fbJavaRoot = root.resolve(root.resolve("java/com/facebook"));
    Files.createDirectories(fbJavaRoot);

    for (int i = 0; i < targetCount; i++) {
      Path targetRoot = fbJavaRoot.resolve(String.format("target_%d", i));
      Files.createDirectories(targetRoot);
      Path buckFile = targetRoot.resolve("BUCK");
      Files.createFile(buckFile);
      Files.write(
          buckFile,
          ("java_library(name = 'foo', srcs = ['A.java'])\n" + "genrule(name = 'baz', out = '')\n")
              .getBytes(StandardCharsets.UTF_8));
      Path javaFile = targetRoot.resolve("A.java");
      Files.createFile(javaFile);
      Files.write(
          javaFile,
          String.format("package com.facebook.target_%d; class A {}", i)
              .getBytes(StandardCharsets.UTF_8));
    }

    ImmutableMap.Builder<String, ImmutableMap<String, String>> configSectionsBuilder =
        ImmutableMap.builder();
    if (threadCount > 1) {
      configSectionsBuilder.put(
          "project",
          ImmutableMap.of(
              "parallel_parsing", "true", "parsing_threads", Integer.toString(threadCount)));
    }
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(configSectionsBuilder.build())
            .build();

    cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    executorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(threadCount));
    parser = TestParserFactory.create(cell);
  }

  @After
  @AfterExperiment
  public void cleanup() {
    tempDir.after();
    executorService.shutdown();
  }

  @Test
  public void parseMultipleTargetsCorrectness() throws Exception {
    parseMultipleTargets();
  }

  @Benchmark
  public void parseMultipleTargets() throws Exception {
    parser.buildTargetGraphWithConfigurationTargets(
        ParsingContext.builder(cell, executorService)
            .setSpeculativeParsing(SpeculativeParsing.ENABLED)
            .build(),
        ImmutableList.of(
            ImmutableTargetNodePredicateSpec.of(
                BuildFileSpec.fromRecursivePath(Paths.get(""), cell.getRoot()))),
        EmptyTargetConfiguration.INSTANCE);
  }
}
