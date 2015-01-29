/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.java.FakeJavaPackageFinder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.FakeRepositoryFactory;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestRepositoryBuilder;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk7.Jdk7Module;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Paths;

public class AuditInputCommandTest {

  private TestConsole console;
  private AuditInputCommand auditInputCommand;

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws IOException, InterruptedException{
    console = new TestConsole();
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    projectFilesystem.touch(Paths.get("src/com/facebook/AndroidLibraryTwo.java"));
    projectFilesystem.touch(Paths.get("src/com/facebook/TestAndroidLibrary.java"));
    projectFilesystem.touch(Paths.get("src/com/facebook/TestJavaLibrary.java"));
    Repository repository = new TestRepositoryBuilder().setFilesystem(projectFilesystem).build();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new Jdk7Module());

    auditInputCommand = new AuditInputCommand(new CommandRunnerParams(
        console,
        new FakeRepositoryFactory(),
        repository,
        new FakeAndroidDirectoryResolver(),
        new InstanceArtifactCacheFactory(artifactCache),
        eventBus,
        new ParserConfig(new FakeBuckConfig()),
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()),
        new FakeJavaPackageFinder(),
        objectMapper,
        FakeFileHashCache.EMPTY_CACHE));
  }

  @Test
  public void testJsonClassPathOutput() throws IOException {
    final String expectedJson = Joiner.on("").join(
        "{",
        "\"//:test-android-library\":",
        "[",
        "\"src/com/facebook/AndroidLibraryTwo.java\",",
        "\"src/com/facebook/TestAndroidLibrary.java\"",
        "],",
        "\"//:test-java-library\":",
        "[",
        "\"src/com/facebook/TestJavaLibrary.java\"",
        "]",
        "}");

    BuildTarget rootTarget = BuildTargetFactory.newInstance("//:test-java-library");
    TargetNode<?> rootNode = JavaLibraryBuilder
        .createBuilder(rootTarget)
        .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
        .build();

    BuildTarget libraryTarget = BuildTargetFactory.newInstance("//:test-android-library");
    TargetNode<?> libraryNode = JavaLibraryBuilder
        .createBuilder(libraryTarget)
        .addSrc(Paths.get("src/com/facebook/TestAndroidLibrary.java"))
        .addSrc(Paths.get("src/com/facebook/AndroidLibraryTwo.java"))
        .addDep(rootTarget)
        .build();

    ImmutableSet<TargetNode<?>> nodes = ImmutableSet.of(rootNode, libraryNode);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);

    auditInputCommand.printJsonInputs(targetGraph);
    assertEquals(expectedJson, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }

  @Test
  public void testNonExistentInputFileThrows() throws IOException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
      "Target //:test-java-library refers to non-existent input file: " +
      "src/com/facebook/NonExistentFile.java");

    BuildTarget rootTarget = BuildTargetFactory.newInstance("//:test-java-library");
    TargetNode<?> rootNode = JavaLibraryBuilder
        .createBuilder(rootTarget)
        .addSrc(Paths.get("src/com/facebook/NonExistentFile.java"))
        .build();

    ImmutableSet<TargetNode<?>> nodes = ImmutableSet.<TargetNode<?>>of(rootNode);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);
    auditInputCommand.printJsonInputs(targetGraph);
  }

  @Test
  public void testJsonContainsRulesWithNoFiles() throws IOException {
    final String expectedJson = Joiner.on("").join(
        "{",
        "\"//:test-exported-dep\":",
        "[",
        "],",
        "\"//:test-java-library\":",
        "[",
        "\"src/com/facebook/TestJavaLibrary.java\"",
        "]",
        "}");

    BuildTarget exportedTarget = BuildTargetFactory.newInstance("//:test-java-library");
    TargetNode<?> exportedNode = JavaLibraryBuilder
        .createBuilder(exportedTarget)
        .addSrc(Paths.get("src/com/facebook/TestJavaLibrary.java"))
        .build();

    BuildTarget rootTarget = BuildTargetFactory.newInstance("//:test-exported-dep");
    TargetNode<?> rootNode = JavaLibraryBuilder
        .createBuilder(rootTarget)
        .addExportedDep(exportedTarget)
        .build();

    ImmutableSet<TargetNode<?>> nodes = ImmutableSet.of(rootNode, exportedNode);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodes);

    auditInputCommand.printJsonInputs(targetGraph);
    assertEquals(expectedJson, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }
}
