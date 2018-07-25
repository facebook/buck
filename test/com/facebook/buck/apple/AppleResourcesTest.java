/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

public class AppleResourcesTest {

  @Test
  public void libWithSingleResourceDepReturnsResource() {
    BuildTarget resourceTarget = BuildTargetFactory.newInstance("//foo:resource");

    Set<SourcePath> variants =
        ImmutableSet.of(
            FakeSourcePath.of("path/aa.lproj/Localizable.strings"),
            FakeSourcePath.of("path/bb.lproj/Localizable.strings"),
            FakeSourcePath.of("path/cc.lproj/Localizable.strings"));

    TargetNode<AppleResourceDescriptionArg> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setFiles(ImmutableSet.of(FakeSourcePath.of("foo.png")))
            .setDirs(ImmutableSet.of())
            .setVariants(variants)
            .build();
    TargetNode<AppleLibraryDescriptionArg> libNode =
        AppleLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//foo:lib"))
            .setDeps(ImmutableSortedSet.of(resourceTarget))
            .build();
    ImmutableSet<TargetNode<?>> graphNodes = ImmutableSet.of(resourceNode, libNode);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(graphNodes);

    assertThat(
        AppleResources.collectRecursiveResources(targetGraph, Optional.empty(), libNode),
        hasItem(resourceNode.getConstructorArg()));
  }

  @Test
  public void libWithTransitiveResourceDepReturnsAllResources() {
    BuildTarget fooResourceTarget = BuildTargetFactory.newInstance("//foo:resource");
    TargetNode<AppleResourceDescriptionArg> fooResourceNode =
        AppleResourceBuilder.createBuilder(fooResourceTarget)
            .setFiles(ImmutableSet.of(FakeSourcePath.of("foo.png")))
            .setDirs(ImmutableSet.of())
            .build();
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<AppleLibraryDescriptionArg> fooLibNode =
        AppleLibraryBuilder.createBuilder(fooLibTarget)
            .setDeps(ImmutableSortedSet.of(fooResourceTarget))
            .build();
    BuildTarget barResourceTarget = BuildTargetFactory.newInstance("//bar:resource");
    TargetNode<AppleResourceDescriptionArg> barResourceNode =
        AppleResourceBuilder.createBuilder(barResourceTarget)
            .setFiles(ImmutableSet.of(FakeSourcePath.of("bar.png")))
            .setDirs(ImmutableSet.of())
            .build();
    TargetNode<AppleLibraryDescriptionArg> barLibNode =
        AppleLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//bar:lib"))
            .setDeps(ImmutableSortedSet.of(fooLibTarget, barResourceTarget))
            .build();
    ImmutableSet<TargetNode<?>> graphNodes =
        ImmutableSet.of(fooResourceNode, fooLibNode, barResourceNode, barLibNode);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(graphNodes);

    assertThat(
        AppleResources.collectRecursiveResources(targetGraph, Optional.empty(), barLibNode),
        hasItems(fooResourceNode.getConstructorArg(), barResourceNode.getConstructorArg()));
  }
}
