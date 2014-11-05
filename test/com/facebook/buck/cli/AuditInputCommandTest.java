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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.java.FakeJavaPackageFinder;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.FakeRepositoryFactory;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestRepositoryBuilder;
import com.facebook.buck.testutil.BuckTestConstant;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class AuditInputCommandTest {

  private TestConsole console;
  private AuditInputCommand auditInputCommand;

  @Before
  public void setUp() throws IOException, InterruptedException{
    console = new TestConsole();
    Repository repository = new TestRepositoryBuilder().build();
    ArtifactCache artifactCache = new NoopArtifactCache();
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();

    auditInputCommand = new AuditInputCommand(new CommandRunnerParams(
        console,
        new FakeRepositoryFactory(),
        repository,
        new FakeAndroidDirectoryResolver(),
        new InstanceArtifactCacheFactory(artifactCache),
        eventBus,
        BuckTestConstant.PYTHON_INTERPRETER,
        BuckTestConstant.ALLOW_EMPTY_GLOBS,
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()),
        new FakeJavaPackageFinder(),
        new ObjectMapper(),
        FakeFileHashCache.EMPTY_CACHE));
  }

  private static final String EXPECTED_JSON = Joiner.on("").join(
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

  @Test
  public void testJsonClassPathOutput() throws IOException {
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
    assertEquals(EXPECTED_JSON, console.getTextWrittenToStdOut());
    assertEquals("", console.getTextWrittenToStdErr());
  }

}
