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
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.Console;
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

public class ParserBenchmark {
  @Param({"10", "100", "500"})
  private int targetCount = 10;

  public DebuggableTemporaryFolder tempDir = new DebuggableTemporaryFolder();

  private ParserNg parser;
  private Parser legacyParser;
  private ParserConfig legacyParserConfig;
  private ProjectFilesystem filesystem;
  private Cell cell;
  private BuckEventBus eventBus;
  private Console console;

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

    BuckConfig config = FakeBuckConfig.builder()
        .setFilesystem(filesystem)
        .build();

    cell = new TestCellBuilder()
        .setFilesystem(filesystem)
        .setBuckConfig(config)
        .build();

    eventBus = BuckEventBusFactory.newInstance();

    parser = new ParserNg();
    legacyParser = Parser.createBuildFileParser(
        cell,
        ParserConfig.AllowSymlinks.FORBID);
    legacyParserConfig = new ParserConfig(config);
    console = new TestConsole();
  }

  @After
  @AfterExperiment
  public void cleanup() {
    tempDir.delete();
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
        ImmutableList.of(
            TargetNodePredicateSpec.of(
                Predicates.alwaysTrue(),
                BuildFileSpec.fromRecursivePath(Paths.get("")))));
  }

  @Test
  public void parseMultipleTargetsLegacyCorrectness() throws Exception {
    parseMultipleTargetsLegacy();
  }

  @Macrobenchmark
  public void parseMultipleTargetsLegacy() throws Exception {
    legacyParser.buildTargetGraphForTargetNodeSpecs(
        ImmutableList.of(
            TargetNodePredicateSpec.of(
                Predicates.alwaysTrue(),
                BuildFileSpec.fromRecursivePath(Paths.get("")))),
        legacyParserConfig,
        eventBus,
        console,
        ImmutableMap.<String, String>of(),
        /* enableProfiling */ false);
  }
}
