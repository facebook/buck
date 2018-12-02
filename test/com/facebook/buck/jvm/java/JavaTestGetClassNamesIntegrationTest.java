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

import static org.easymock.EasyMock.createMock;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class JavaTestGetClassNamesIntegrationTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private ProjectFilesystem projectFilesystem;
  private SourcePathResolver resolver;

  @Before
  public void setUp() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "java_test_get_class_names", temporaryFolder);
    workspace.setUp();
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    resolver = DefaultSourcePathResolver.from(createMock(SourcePathRuleFinder.class));
  }

  @Test
  public void testGetClassNamesForSources() {
    Path classesFolder = Paths.get("default.jar");
    Set<SourcePath> sources = ImmutableSet.of(makeSourcePath("src/com/facebook/DummyTest.java"));
    Set<String> classNames =
        JavaTest.CompiledClassFileFinder.getClassNamesForSources(
            sources, classesFolder, projectFilesystem, resolver);
    assertEquals(ImmutableSet.of("com.facebook.DummyTest"), classNames);
  }

  @Test
  public void testGetClassNamesForSourcesWithInnerClasses() {
    Path classesFolder = Paths.get("case1.jar");
    Set<SourcePath> sources = ImmutableSet.of(makeSourcePath("src/com/facebook/DummyTest.java"));
    Set<String> classNames =
        JavaTest.CompiledClassFileFinder.getClassNamesForSources(
            sources, classesFolder, projectFilesystem, resolver);
    assertEquals(ImmutableSet.of("com.facebook.DummyTest"), classNames);
  }

  @Test
  public void testGetClassNamesForSourcesWithMultipleTopLevelClasses() {
    Path classesFolder = Paths.get("case2.jar");
    Set<SourcePath> sources = ImmutableSet.of(makeSourcePath("src/com/facebook/DummyTest.java"));
    Set<String> classNames =
        JavaTest.CompiledClassFileFinder.getClassNamesForSources(
            sources, classesFolder, projectFilesystem, resolver);
    assertEquals(ImmutableSet.of("com.facebook.DummyTest"), classNames);
  }

  @Test
  public void testGetClassNamesForSourcesWithImperfectHeuristic() {
    Path classesFolder = Paths.get("case2fail.jar");
    Set<SourcePath> sources =
        ImmutableSet.of(
            makeSourcePath("src/com/facebook/feed/DummyTest.java"),
            makeSourcePath("src/com/facebook/nav/OtherDummyTest.java"));
    Set<String> classNames =
        JavaTest.CompiledClassFileFinder.getClassNamesForSources(
            sources, classesFolder, projectFilesystem, resolver);
    assertEquals(
        "Ideally, if the implementation of getClassNamesForSources() were tightened up,"
            + " the set would not include com.facebook.feed.OtherDummyTest because"
            + " it was not specified in sources.",
        ImmutableSet.of(
            "com.facebook.feed.DummyTest",
            "com.facebook.feed.OtherDummyTest",
            "com.facebook.nav.OtherDummyTest"),
        classNames);
  }

  private SourcePath makeSourcePath(String relative) {
    return PathSourcePath.of(projectFilesystem, Paths.get(relative));
  }
}
