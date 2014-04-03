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

package com.facebook.buck.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import org.easymock.EasyMock;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

public class BuildFileTreeTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private BuildFileTree buildFileTree;

  @Test
  public void testGetChildPaths() {
    PartialGraph graph = createGraphForRules(
        "//:fb4a",
        "//java/com/facebook/common:base",
        "//java/com/facebook/common/rpc:rpc",
        "//java/com/facebook/common/ui:ui",
        "//javatests/com/facebook/common:base",
        "//javatests/com/facebook/common/rpc:rpc",
        "//javatests/com/facebook/common/ui:ui");
    buildFileTree = new BuildFileTree(graph.getTargets());

    assertGetChildPaths("",
        ImmutableSet.of("java/com/facebook/common", "javatests/com/facebook/common"));
    assertGetChildPaths("java/com/facebook/common",
        ImmutableSet.of("rpc", "ui"));
    assertGetChildPaths("java/com/facebook/common/rpc",
        ImmutableSet.<String>of());
  }

  @Test @Ignore("Remove when test passes on OS X (the case preserving file system hurts us)")
  public void testCanConstructBuildFileTreeFromFilesystemOnOsX() throws IOException {
    File tempDir = tmp.getRoot();
    ProjectFilesystem filesystem = new ProjectFilesystem(tempDir);

    File command = new File(tempDir, "src/com/facebook/buck/command");
    assertTrue(command.mkdirs());
    File notbuck = new File(tempDir, "src/com/facebook/buck/notbuck");
    assertTrue(notbuck.mkdirs());

    // Although these next two lines create a file and a directory, the OS X filesystem is often
    // case insensitive. As we run File.listFiles only the directory entry is returned. Thanks OS X.
    Files.touch(new File(tempDir, "src/com/facebook/BUCK"));
    Files.touch(new File(tempDir, "src/com/facebook/buck/BUCK"));
    Files.touch(new File(tempDir, "src/com/facebook/buck/command/BUCK"));
    Files.touch(new File(tempDir, "src/com/facebook/buck/notbuck/BUCK"));

    BuildFileTree buildFiles = BuildFileTree.constructBuildFileTree(filesystem);
    Iterable<String> allChildren = buildFiles.getChildPaths("src/com/facebook");
    assertEquals(ImmutableSet.of("buck"),
        ImmutableSet.copyOf(allChildren));

    Iterable<String> subChildren = buildFiles.getChildPaths("src/com/facebook/buck");
    assertEquals(ImmutableSet.of("command", "notbuck"),
        ImmutableSet.copyOf(subChildren));
  }

  @Test
  public void testCanConstructBuildFileTreeFromFilesystem() throws IOException {
    File tempDir = tmp.getRoot();
    ProjectFilesystem filesystem = new ProjectFilesystem(tempDir);

    File command = new File(tempDir, "src/com/example/build/command");
    assertTrue(command.mkdirs());
    File notbuck = new File(tempDir, "src/com/example/build/notbuck");
    assertTrue(notbuck.mkdirs());

    Files.touch(new File(tempDir, "src/com/example/BUCK"));
    Files.touch(new File(tempDir, "src/com/example/build/BUCK"));
    Files.touch(new File(tempDir, "src/com/example/build/command/BUCK"));
    Files.touch(new File(tempDir, "src/com/example/build/notbuck/BUCK"));

    BuildFileTree buildFiles = BuildFileTree.constructBuildFileTree(filesystem);
    Iterable<String> allChildren = buildFiles.getChildPaths("src/com/example");
    assertEquals(ImmutableSet.of("build"),
        ImmutableSet.copyOf(allChildren));

    Iterable<String> subChildren = buildFiles.getChildPaths("src/com/example/build");
    assertEquals(ImmutableSet.of("command", "notbuck"),
        ImmutableSet.copyOf(subChildren));

  }

  private void assertGetChildPaths(String parent, Set<String> expectedChildren) {
    Set<String> children = ImmutableSet.copyOf(buildFileTree.getChildPaths(parent));
    assertEquals(expectedChildren, children);
  }

  private static PartialGraph createGraphForRules(String... ruleNames) {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    List<BuildTarget> targets = Lists.newArrayList();
    for (String ruleName : ruleNames) {
      BuildTarget buildTarget = BuildTargetFactory.newInstance(ruleName);
      JavaLibraryBuilder.createBuilder(buildTarget).build(ruleResolver);
      targets.add(buildTarget);
    }

    PartialGraph partialGraph = EasyMock.createMock(PartialGraph.class);
    EasyMock.expect(partialGraph.getTargets()).andReturn(targets);
    EasyMock.replay(partialGraph);
    return partialGraph;
  }
}
