/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.easymock.EasyMock;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class BuildTargetSourcePathTest {

  private BuildTarget target = BuildTargetFactory.newInstance("//example:target");

  @Test(expected = NullPointerException.class)
  public void requiresBuildTargetToNotBeNull() {
    new BuildTargetSourcePath(null);
  }

  @Test
  public void shouldThrowAnExceptionIfBuildTargetDoesNotResolveToARule() {
    DependencyGraph graph = new DependencyGraph(new MutableDirectedGraph<BuildRule>());

    BuildContext context = EasyMock.createNiceMock(BuildContext.class);
    EasyMock.expect(context.getDependencyGraph()).andStubReturn(graph);
    EasyMock.replay(context);

    BuildTargetSourcePath path = new BuildTargetSourcePath(target);

    try {
      path.resolve(context);
      fail();
    } catch (HumanReadableException e) {
      assertEquals("Cannot resolve: " + target.getFullyQualifiedName(), e.getMessage());
    }

    EasyMock.verify(context);
  }

  @Test
  public void shouldThrowAnExceptionIfRuleDoesNotHaveAnOutput() {
    MutableDirectedGraph<BuildRule> dag = new MutableDirectedGraph<>();
    dag.addNode(new FakeBuildRule(
        new BuildRuleType("example"),
        target,
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of())
    );

    DependencyGraph graph = new DependencyGraph(dag);

    BuildContext context = EasyMock.createNiceMock(BuildContext.class);
    EasyMock.expect(context.getDependencyGraph()).andStubReturn(graph);
    EasyMock.replay(context);

    BuildTargetSourcePath path = new BuildTargetSourcePath(target);

    try {
      path.resolve(context);
      fail();
    } catch (HumanReadableException e) {
      assertEquals("No known output for: " + target.getFullyQualifiedName(), e.getMessage());
    }

    EasyMock.verify(context);
  }

  @Test
  public void mustUseProjectFilesystemToResolvePathToFile() {
    MutableDirectedGraph<BuildRule> dag = new MutableDirectedGraph<>();
    dag.addNode(new FakeBuildRule(
        new BuildRuleType("example"),
        target,
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of()) {
      @Override
      public Path getPathToOutputFile() {
        return Paths.get("cheese");
      }
    });

    DependencyGraph graph = new DependencyGraph(dag);

    BuildContext context = EasyMock.createNiceMock(BuildContext.class);
    EasyMock.expect(context.getDependencyGraph()).andStubReturn(graph);
    EasyMock.replay(context);

    BuildTargetSourcePath path = new BuildTargetSourcePath(target);

    Path resolved = path.resolve(context);

    assertEquals(Paths.get("cheese"), resolved);
    EasyMock.verify(context);
  }

  @Test
  public void shouldReturnFullyQualifiedBuildTargetAsAStringAsTheReference() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");

    BuildTargetSourcePath path = new BuildTargetSourcePath(target);

    assertEquals(target.getFullyQualifiedName(), path.asReference());
  }
}
