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

package com.facebook.buck.model;

import static com.facebook.buck.io.file.MorePaths.pathWithPlatformSeparators;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class InMemoryBuildFileTreeTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private InMemoryBuildFileTree buildFileTree;

  @Test
  public void testGetChildPaths() {
    ImmutableSet<BuildTarget> targets =
        ImmutableSet.of(
            BuildTargetFactory.newInstance("//:fb4a"),
            BuildTargetFactory.newInstance("//java/com/facebook/common:base"),
            BuildTargetFactory.newInstance("//java/com/facebook/common/rpc:rpc"),
            BuildTargetFactory.newInstance("//java/com/facebook/common/ui:ui"),
            BuildTargetFactory.newInstance("//javatests/com/facebook/common:base"),
            BuildTargetFactory.newInstance("//javatests/com/facebook/common/rpc:rpc"),
            BuildTargetFactory.newInstance("//javatests/com/facebook/common/ui:ui"));
    buildFileTree = new InMemoryBuildFileTree(targets);

    assertGetChildPaths(
        "",
        ImmutableSet.of(
            pathWithPlatformSeparators("java/com/facebook/common"),
            pathWithPlatformSeparators("javatests/com/facebook/common")));
    assertGetChildPaths("java/com/facebook/common", ImmutableSet.of("rpc", "ui"));
    assertGetChildPaths("java/com/facebook/common/rpc", ImmutableSet.of());
  }

  private void assertGetChildPaths(String parent, Set<String> expectedChildren) {
    Collection<Path> children = ImmutableSet.copyOf(buildFileTree.getChildPaths(Paths.get(parent)));

    assertEquals(
        expectedChildren,
        children.stream().map(Object::toString).collect(ImmutableSet.toImmutableSet()));
  }
}
