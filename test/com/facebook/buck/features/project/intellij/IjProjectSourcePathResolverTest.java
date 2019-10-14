/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.features.project.intellij;

import static org.junit.Assert.*;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.features.filegroup.FileGroupDescriptionArg;
import com.facebook.buck.features.filegroup.FilegroupBuilder;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;

public class IjProjectSourcePathResolverTest {

  private FakeProjectFilesystem filesystem;

  @Before
  public void setUp() throws Exception {
    filesystem = new FakeProjectFilesystem();
  }

  @Test
  public void testFilegroup() {
    TargetNode<FileGroupDescriptionArg> node =
        FilegroupBuilder.createBuilder(BuildTargetFactory.newInstance("//files:group"))
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("file.txt")))
            .build(filesystem);
    assertOutputPathsEqual(node);
  }

  /**
   * Create the BuildRule for the given node and assert that the sourcePathToOutput matches what is
   * returned by the IjProjectSourcePathResolver based solely on the node definition.
   */
  private void assertOutputPathsEqual(TargetNode<?> targetNode) {
    TargetGraph targetGraph = TargetGraphFactory.newInstance(targetNode);
    assertOutputPathsEqual(targetGraph, targetNode.getBuildTarget());
  }

  /**
   * Assert that the given target in the graph calculates the same output when instantiated into a
   * real build rule as the output that we infer/guess in IjProjectSourcePathResolver
   */
  private void assertOutputPathsEqual(TargetGraph targetGraph, BuildTarget target) {
    TargetNode<?> node = targetGraph.get(target);
    DefaultBuildTargetSourcePath toResolve = DefaultBuildTargetSourcePath.of(node.getBuildTarget());

    // Calculate the real path
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    SourcePath sourcePathToOutput =
        graphBuilder.requireRule(node.getBuildTarget()).getSourcePathToOutput();
    Path realPath = graphBuilder.getSourcePathResolver().getRelativePath(sourcePathToOutput);

    // Find the guessed path
    IjProjectSourcePathResolver projectSourcePathResolver =
        new IjProjectSourcePathResolver(targetGraph);
    Path guessedPath = projectSourcePathResolver.getRelativePath(toResolve);

    assertEquals(realPath, guessedPath);
  }
}
