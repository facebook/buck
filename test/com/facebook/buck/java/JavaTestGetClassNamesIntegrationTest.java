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

package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

public class JavaTestGetClassNamesIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "java_test_get_class_names",
        temporaryFolder);
    workspace.setUp();
    projectFilesystem = new ProjectFilesystem(temporaryFolder.getRootPath());
  }

  @Test
  public void testGetClassNamesForSources() {
    Path classesFolder = Paths.get("default.jar");
    Set<Path> sources = ImmutableSet.of(
        Paths.get("src/com/facebook/DummyTest.java"));
    Set<String> classNames =
        JavaTest.CompiledClassFileFinder.getClassNamesForSources(
            sources,
            classesFolder,
            projectFilesystem);
    assertEquals(ImmutableSet.of("com.facebook.DummyTest"), classNames);
  }

  @Test
  public void testGetClassNamesForSourcesWithInnerClasses() {
    Path classesFolder = Paths.get("case1.jar");
    Set<Path> sources = ImmutableSet.of(
        Paths.get("src/com/facebook/DummyTest.java"));
    Set<String> classNames =
        JavaTest.CompiledClassFileFinder.getClassNamesForSources(
            sources,
            classesFolder,
            projectFilesystem);
    assertEquals(ImmutableSet.of("com.facebook.DummyTest"), classNames);
  }

  @Test
  public void testGetClassNamesForSourcesWithMultipleTopLevelClasses() {
    Path classesFolder = Paths.get("case2.jar");
    Set<Path> sources = ImmutableSet.of(
        Paths.get("src/com/facebook/DummyTest.java"));
    Set<String> classNames =
        JavaTest.CompiledClassFileFinder.getClassNamesForSources(
            sources,
            classesFolder,
            projectFilesystem);
    assertEquals(ImmutableSet.of("com.facebook.DummyTest"), classNames);
  }

  @Test
  public void testGetClassNamesForSourcesWithImperfectHeuristic() {
    Path classesFolder = Paths.get("case2fail.jar");
    Set<Path> sources = ImmutableSet.of(
        Paths.get("src/com/facebook/feed/DummyTest.java"),
        Paths.get("src/com/facebook/nav/OtherDummyTest.java"));
    Set<String> classNames =
        JavaTest.CompiledClassFileFinder.getClassNamesForSources(
            sources,
            classesFolder,
            projectFilesystem);
    assertEquals("Ideally, if the implementation of getClassNamesForSources() were tightened up," +
        " the set would not include com.facebook.feed.OtherDummyTest because" +
        " it was not specified in sources.",
        ImmutableSet.of(
            "com.facebook.feed.DummyTest",
            "com.facebook.feed.OtherDummyTest",
            "com.facebook.nav.OtherDummyTest"
        ),
        classNames);
  }
}
