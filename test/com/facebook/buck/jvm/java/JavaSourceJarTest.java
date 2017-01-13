/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

public class JavaSourceJarTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void outputNameShouldIndicateThatTheOutputIsASrcJar() {
    JavaSourceJar rule = new JavaSourceJar(
        new FakeBuildRuleParamsBuilder("//example:target").build(),
        new SourcePathResolver(new SourcePathRuleFinder(
            new BuildRuleResolver(
              TargetGraph.EMPTY,
              new DefaultTargetNodeToBuildRuleTransformer())
        )),
        Optional.empty(),
        Optional.empty(),
        ImmutableSortedSet.of(),
        ImmutableSortedSet.of());

    Path output = rule.getPathToOutput();

    assertNotNull(output);
    assertTrue(output.toString().endsWith(Javac.SRC_JAR));
  }

  @Test
  public void shouldIncludeSourcesFromBuildTargetsAndPlainPaths() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "src-jar",
        tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:lib#src");


    Zip zip = new Zip(output, /* for writing? */ false);
    Set<String> fileNames = zip.getFileNames();

    assertTrue(fileNames.contains("com/example/Direct.java"));
    assertTrue(fileNames.contains("com/example/Generated.java"));

    // output should not contain a transitive dep
    assertFalse(fileNames.contains("com/example/Transitive.java"));
  }

  @Test
  public void shouldNotIncludeNonJavaFiles() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "src-jar",
        tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:lib#src");


    Zip zip = new Zip(output, /* for writing? */ false);
    Set<String> fileNames = zip.getFileNames();

    assertFalse(fileNames.contains("com/example/hello.txt"));
  }

  @Test
  public void shouldBuildMavenisedSourceJars() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "src-jar",
        tmp);
    workspace.setUp();

    Path output = workspace.buildAndReturnOutput("//:lib#maven,src");


    Zip zip = new Zip(output, /* for writing? */ false);
    Set<String> fileNames = zip.getFileNames();

    // output should not contain any files from "//:mvn-dep"
    assertFalse(fileNames.contains("com/example/MavenSource.java"));

    // output should contain a transitive dep
    assertTrue(fileNames.contains("com/example/Transitive.java"));

    // output should contain a direct dep
    assertTrue(fileNames.contains("com/example/Direct.java"));
  }
}
