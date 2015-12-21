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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;
import com.google.caliper.Param;
import com.google.caliper.api.Macrobenchmark;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ParserBenchmark {
  @Param({"10", "100", "500"})
  private int targetCount = 10;

  @Param({"1", "2", "10"})
  private int threadCount = 1;

  public DebuggableTemporaryFolder tempDir = new DebuggableTemporaryFolder();

  private Parser parser;
  private ProjectFilesystem filesystem;
  private Cell cell;
  private BuckEventBus eventBus;
  private ExecutorService executorService;

  @Before
  public void setUpTest() throws Exception {
    targetCount = 10;
    setUpBenchmark();
  }

  @BeforeExperiment
  public void setUpBenchmark() throws Exception {
    tempDir.create();
    Path root = tempDir.getRootPath();
    Files.createDirectories(root);
    filesystem = new ProjectFilesystem(root);

    Path fbJavaRoot = root.resolve(root.resolve("java/com/facebook"));
    Files.createDirectories(fbJavaRoot);

    for (int i = 0; i < targetCount; i++) {
      Path targetRoot = fbJavaRoot.resolve(String.format("target_%d", i));
      Files.createDirectories(targetRoot);
      Path buckFile = targetRoot.resolve("BUCK");
      Files.createFile(buckFile);
      Files.write(
          buckFile,
          ("java_library(name = 'foo', srcs = ['A.java'])\n" +
              "genrule(name = 'baz', out = '')\n").getBytes("UTF-8"));
      Path javaFile = targetRoot.resolve("A.java");
      Files.createFile(javaFile);
      Files.write(
          javaFile,
          String.format("package com.facebook.target_%d; class A {}", i).getBytes("UTF-8"));
    }

    ImmutableMap.Builder<String, ImmutableMap<String, String>> configSectionsBuilder =
        ImmutableMap.builder();
    if (threadCount > 1) {
      configSectionsBuilder.put(
          "project",
          ImmutableMap.of(
              "parallel_parsing", "true",
              "parsing_threads", Integer.toString(threadCount)));
    }
    BuckConfig config = FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .setSections(configSectionsBuilder.build())
        .build();

    cell = new TestCellBuilder()
        .setFilesystem(filesystem)
        .setBuckConfig(config)
        .build();

    eventBus = BuckEventBusFactory.newInstance();
    executorService = Executors.newFixedThreadPool(threadCount);

    DefaultTypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ConstructorArgMarshaller marshaller = new ConstructorArgMarshaller(typeCoercerFactory);
    parser = new Parser(
        new ParserConfig(config),
        typeCoercerFactory,
        marshaller);
  }

  @After
  @AfterExperiment
  public void cleanup() {
    tempDir.delete();
    executorService.shutdown();
  }

  @Test
  public void parseMultipleTargetsCorrectness() throws Exception {
    parseMultipleTargets();
  }

  @Macrobenchmark
  public void parseMultipleTargets() throws Exception {
    parser.buildTargetGraphForTargetNodeSpecs(
        eventBus,
        cell,
        /* enableProfiling */ false,
        executorService,
        ImmutableList.of(
            TargetNodePredicateSpec.of(
                Predicates.alwaysTrue(),
                BuildFileSpec.fromRecursivePath(Paths.get("")))));
  }
}
