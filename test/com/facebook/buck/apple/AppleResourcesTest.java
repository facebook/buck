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

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

public class AppleResourcesTest {

  @Test
  public void emptyInputHasEmptyResources() {
    ImmutableSet<TargetNode<?, ?>> graphNodes = ImmutableSet.of();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(graphNodes);
    ImmutableSet<TargetNode<AppleResourceDescriptionArg, ?>> targetNodes = ImmutableSet.of();

    assertThat(AppleResources.collectRecursiveResources(targetGraph, null, targetNodes), empty());
  }

  @Test
  public void libWithSingleResourceDepReturnsResource() {
    BuildTarget resourceTarget = BuildTargetFactory.newInstance("//foo:resource");

    Set<SourcePath> variants =
        ImmutableSet.of(
            new FakeSourcePath("path/aa.lproj/Localizable.strings"),
            new FakeSourcePath("path/bb.lproj/Localizable.strings"),
            new FakeSourcePath("path/cc.lproj/Localizable.strings"));

    TargetNode<AppleResourceDescriptionArg, ?> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setFiles(ImmutableSet.of(new FakeSourcePath("foo.png")))
            .setDirs(ImmutableSet.of())
            .setVariants(variants)
            .build();
    TargetNode<AppleLibraryDescriptionArg, ?> libNode =
        AppleLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//foo:lib"))
            .setDeps(ImmutableSortedSet.of(resourceTarget))
            .build();
    ImmutableSet<TargetNode<?, ?>> graphNodes = ImmutableSet.of(resourceNode, libNode);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(graphNodes);
    ImmutableSet<TargetNode<AppleLibraryDescriptionArg, ?>> targetNodes = ImmutableSet.of(libNode);

    assertThat(
        AppleResources.collectRecursiveResources(targetGraph, Optional.empty(), targetNodes),
        hasItem(resourceNode.getConstructorArg()));
  }

  @Test
  public void libWithTransitiveResourceDepReturnsAllResources() {
    BuildTarget fooResourceTarget = BuildTargetFactory.newInstance("//foo:resource");
    TargetNode<AppleResourceDescriptionArg, ?> fooResourceNode =
        AppleResourceBuilder.createBuilder(fooResourceTarget)
            .setFiles(ImmutableSet.of(new FakeSourcePath("foo.png")))
            .setDirs(ImmutableSet.of())
            .build();
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<AppleLibraryDescriptionArg, ?> fooLibNode =
        AppleLibraryBuilder.createBuilder(fooLibTarget)
            .setDeps(ImmutableSortedSet.of(fooResourceTarget))
            .build();
    BuildTarget barResourceTarget = BuildTargetFactory.newInstance("//bar:resource");
    TargetNode<AppleResourceDescriptionArg, ?> barResourceNode =
        AppleResourceBuilder.createBuilder(barResourceTarget)
            .setFiles(ImmutableSet.of(new FakeSourcePath("bar.png")))
            .setDirs(ImmutableSet.of())
            .build();
    TargetNode<AppleLibraryDescriptionArg, ?> barLibNode =
        AppleLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//bar:lib"))
            .setDeps(ImmutableSortedSet.of(fooLibTarget, barResourceTarget))
            .build();
    ImmutableSet<TargetNode<?, ?>> graphNodes =
        ImmutableSet.of(fooResourceNode, fooLibNode, barResourceNode, barLibNode);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(graphNodes);
    ImmutableSet<TargetNode<AppleLibraryDescriptionArg, ?>> targetNodes =
        ImmutableSet.of(barLibNode);

    assertThat(
        AppleResources.collectRecursiveResources(targetGraph, Optional.empty(), targetNodes),
        hasItems(fooResourceNode.getConstructorArg(), barResourceNode.getConstructorArg()));
  }
}
