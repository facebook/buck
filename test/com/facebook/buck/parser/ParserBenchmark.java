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
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
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
import org.pf4j.PluginManager;

public class ParserBenchmark {
  @Param({"10", "100", "500"})
  private int targetCount = 10;

  @Param({"1", "2", "10"})
  private int threadCount = 1;

  private TemporaryPaths tempDir = new TemporaryPaths();

  private Parser parser;
  private ProjectFilesystem filesystem;
  private Cell cell;
  private BuckEventBus eventBus;
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
    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(pluginManager);

    eventBus = BuckEventBusForTests.newInstance();
    executorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(threadCount));

    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ConstructorArgMarshaller marshaller = new ConstructorArgMarshaller(typeCoercerFactory);
    ParserConfig parserConfig = config.getView(ParserConfig.class);
    parser =
        new DefaultParser(
            new PerBuildStateFactory(
                typeCoercerFactory,
                marshaller,
                knownRuleTypesProvider,
                new ParserPythonInterpreterProvider(parserConfig, new ExecutableFinder())),
            parserConfig,
            typeCoercerFactory,
            new TargetSpecResolver());
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
    parser.buildTargetGraphForTargetNodeSpecs(
        eventBus,
        cell,
        /* enableProfiling */ false,
        executorService,
        ImmutableList.of(
            TargetNodePredicateSpec.of(
                BuildFileSpec.fromRecursivePath(Paths.get(""), cell.getRoot()))));
  }
}
