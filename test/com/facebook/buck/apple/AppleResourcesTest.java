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
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.util.Set;

public class AppleResourcesTest {

  @Test
  public void emptyInputHasEmptyResources() {
    ImmutableSet<TargetNode<?>> graphNodes = ImmutableSet.of();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(graphNodes);
    ImmutableSet<TargetNode<AppleResourceDescription.Arg>> targetNodes = ImmutableSet.of();

    assertThat(
        AppleResources.collectRecursiveResources(
            targetGraph,
            targetNodes),
        empty());
  }

  @Test
  public void libWithSingleResourceDepReturnsResource() {
    BuildTarget resourceTarget = BuildTargetFactory.newInstance("//foo:resource");

    Set<SourcePath> variants = ImmutableSet.<SourcePath>of(
        new FakeSourcePath("path/aa.lproj/Localizable.strings"),
        new FakeSourcePath("path/bb.lproj/Localizable.strings"),
        new FakeSourcePath("path/cc.lproj/Localizable.strings"));

    TargetNode<AppleResourceDescription.Arg> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setFiles(ImmutableSet.<SourcePath>of(new FakeSourcePath("foo.png")))
            .setDirs(ImmutableSet.<SourcePath>of())
            .setVariants(Optional.of(variants))
            .build();
    TargetNode<AppleNativeTargetDescriptionArg> libNode = AppleLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//foo:lib"))
        .setDeps(Optional.of(ImmutableSortedSet.of(resourceTarget)))
        .build();
    ImmutableSet<TargetNode<?>> graphNodes = ImmutableSet.of(
        resourceNode,
        libNode);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(graphNodes);
    ImmutableSet<TargetNode<AppleNativeTargetDescriptionArg>> targetNodes = ImmutableSet.of(
        libNode);

    assertThat(
        AppleResources.collectRecursiveResources(
            targetGraph,
            targetNodes),
        hasItem(resourceNode.getConstructorArg()));
  }

  @Test
  public void libWithTransitiveResourceDepReturnsAllResources() {
    BuildTarget fooResourceTarget = BuildTargetFactory.newInstance("//foo:resource");
    TargetNode<AppleResourceDescription.Arg> fooResourceNode =
        AppleResourceBuilder.createBuilder(fooResourceTarget)
            .setFiles(ImmutableSet.<SourcePath>of(new FakeSourcePath("foo.png")))
            .setDirs(ImmutableSet.<SourcePath>of())
            .build();
    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<AppleNativeTargetDescriptionArg> fooLibNode = AppleLibraryBuilder
        .createBuilder(fooLibTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(fooResourceTarget)))
        .build();
    BuildTarget barResourceTarget = BuildTargetFactory.newInstance("//bar:resource");
    TargetNode<AppleResourceDescription.Arg> barResourceNode =
        AppleResourceBuilder.createBuilder(barResourceTarget)
            .setFiles(ImmutableSet.<SourcePath>of(new FakeSourcePath("bar.png")))
            .setDirs(ImmutableSet.<SourcePath>of())
            .build();
    TargetNode<AppleNativeTargetDescriptionArg> barLibNode = AppleLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//bar:lib"))
        .setDeps(Optional.of(ImmutableSortedSet.of(fooLibTarget, barResourceTarget)))
        .build();
    ImmutableSet<TargetNode<?>> graphNodes = ImmutableSet.of(
        fooResourceNode,
        fooLibNode,
        barResourceNode,
        barLibNode);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(graphNodes);
    ImmutableSet<TargetNode<AppleNativeTargetDescriptionArg>> targetNodes = ImmutableSet.of(
        barLibNode);

    assertThat(
        AppleResources.collectRecursiveResources(
            targetGraph,
            targetNodes),
        hasItems(fooResourceNode.getConstructorArg(), barResourceNode.getConstructorArg()));
  }
}
