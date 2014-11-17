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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class SrcRootsFinderIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void shouldFindPathsRelativeToRoot() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(temporaryFolder.getRootPath());
    SrcRootsFinder finder = new SrcRootsFinder(projectFilesystem);
    ImmutableSet<String> pathPatterns = ImmutableSet.of("/a/", "/b");
    assertThat(
        "Paths relative to the project root should be returned unmodified.",
        finder.getAllSrcRootPaths(pathPatterns),
        containsInAnyOrder(Paths.get("a"), Paths.get("b")));
  }

  @Test
  public void shouldFindPathsMatchingPathElements() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(temporaryFolder.getRootPath());
    projectFilesystem.mkdirs(Paths.get("java"));
    projectFilesystem.mkdirs(Paths.get("third-party/foo/src"));
    projectFilesystem.mkdirs(Paths.get("not/a/root"));
    SrcRootsFinder finder = new SrcRootsFinder(projectFilesystem);
    ImmutableSet<String> pathPatterns = ImmutableSet.of("src", "java");
    assertThat(
        "Should find paths matching path elements.",
        finder.getAllSrcRootPaths(pathPatterns),
        containsInAnyOrder(Paths.get("java"), Paths.get("third-party/foo/src")));
  }
}
