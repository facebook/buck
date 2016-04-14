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

package com.facebook.buck.rules;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class TargetGraphHashingTest {

  @Test
  public void emptyTargetGraphHasEmptyHashes() throws IOException, InterruptedException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    TestCellBuilder cellBuilder = new TestCellBuilder()
      .setFilesystem(projectFilesystem);
    TargetGraph targetGraph = TargetGraphFactory.newInstance();

    assertThat(
        TargetGraphHashing.hashTargetGraph(
            cellBuilder.build(),
            targetGraph,
            new NullFileHashCache(),
            ImmutableList.<TargetNode<?>>of())
            .entrySet(),
        empty());
  }

  @Test
  public void hashChangesWhenSrcContentChanges() throws IOException, InterruptedException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    TestCellBuilder cellBuilder = new TestCellBuilder()
      .setFilesystem(projectFilesystem);

    TargetNode<?> node = createJavaLibraryTargetNodeWithSrcs(
        BuildTargetFactory.newInstance("//foo:lib"),
        HashCode.fromLong(64738),
        ImmutableSet.of(Paths.get("foo/FooLib.java")));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(node);

    FileHashCache baseCache = new FakeFileHashCache(
        ImmutableMap.of(
            projectFilesystem.resolve("foo/FooLib.java"), HashCode.fromString("abcdef")));

    FileHashCache modifiedCache = new FakeFileHashCache(
        ImmutableMap.of(
            projectFilesystem.resolve("foo/FooLib.java"), HashCode.fromString("abc1ef")));

    Map<TargetNode<?>, HashCode> baseResult = TargetGraphHashing.hashTargetGraph(
        cellBuilder.build(),
        targetGraph,
        baseCache,
        ImmutableList.<TargetNode<?>>of(node));

    Map<TargetNode<?>, HashCode> modifiedResult = TargetGraphHashing.hashTargetGraph(
        cellBuilder.build(),
        targetGraph,
        modifiedCache,
        ImmutableList.<TargetNode<?>>of(node));

    assertThat(baseResult, aMapWithSize(1));
    assertThat(baseResult, Matchers.<TargetNode<?>>hasKey(node));
    assertThat(modifiedResult, aMapWithSize(1));
    assertThat(modifiedResult, Matchers.<TargetNode<?>>hasKey(node));
    assertThat(
        modifiedResult.get(node),
        not(equalTo(baseResult.get(node))));
  }

  @Test
  public void twoNodeIndependentRootsTargetGraphHasExpectedHashes()
      throws IOException, InterruptedException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    TestCellBuilder cellBuilder = new TestCellBuilder()
      .setFilesystem(projectFilesystem);

    TargetNode<?> nodeA = createJavaLibraryTargetNodeWithSrcs(
        BuildTargetFactory.newInstance("//foo:lib"),
        HashCode.fromLong(64738),
        ImmutableSet.of(Paths.get("foo/FooLib.java")));
    TargetNode<?> nodeB = createJavaLibraryTargetNodeWithSrcs(
        BuildTargetFactory.newInstance("//bar:lib"),
        HashCode.fromLong(49152),
        ImmutableSet.of(Paths.get("bar/BarLib.java")));
    TargetGraph targetGraphA = TargetGraphFactory.newInstance(nodeA);
    TargetGraph targetGraphB = TargetGraphFactory.newInstance(nodeB);
    TargetGraph commonTargetGraph = TargetGraphFactory.newInstance(nodeA, nodeB);

    FileHashCache fileHashCache = new FakeFileHashCache(
        ImmutableMap.of(
            projectFilesystem.resolve("foo/FooLib.java"), HashCode.fromString("abcdef"),
            projectFilesystem.resolve("bar/BarLib.java"), HashCode.fromString("123456")));

    Map<TargetNode<?>, HashCode> resultsA = TargetGraphHashing.hashTargetGraph(
        cellBuilder.build(),
        targetGraphA,
        fileHashCache,
        ImmutableList.<TargetNode<?>>of(nodeA));

    Map<TargetNode<?>, HashCode> resultsB = TargetGraphHashing.hashTargetGraph(
        cellBuilder.build(),
        targetGraphB,
        fileHashCache,
        ImmutableList.<TargetNode<?>>of(nodeB));

    Map<TargetNode<?>, HashCode> commonResults = TargetGraphHashing.hashTargetGraph(
        cellBuilder.build(),
        commonTargetGraph,
        fileHashCache,
        ImmutableList.of(nodeA, nodeB));

    assertThat(resultsA, aMapWithSize(1));
    assertThat(resultsA, Matchers.<TargetNode<?>>hasKey(nodeA));
    assertThat(resultsB, aMapWithSize(1));
    assertThat(resultsB, Matchers.<TargetNode<?>>hasKey(nodeB));
    assertThat(commonResults, aMapWithSize(2));
    assertThat(commonResults, Matchers.<TargetNode<?>>hasKey(nodeA));
    assertThat(commonResults, Matchers.<TargetNode<?>>hasKey(nodeB));
    assertThat(
        resultsA.get(nodeA),
        equalTo(commonResults.get(nodeA)));
    assertThat(
        resultsB.get(nodeB),
        equalTo(commonResults.get(nodeB)));
  }

  private TargetGraph createGraphWithANodeAndADep(
      BuildTarget nodeTarget,
      HashCode nodeHash,
      BuildTarget depTarget,
      HashCode depHash) {
    TargetNode<?> dep = createJavaLibraryTargetNodeWithSrcs(
        depTarget,
        depHash,
        ImmutableSet.of(Paths.get("dep/DepLib.java")));
    TargetNode<?> node = createJavaLibraryTargetNodeWithSrcs(
        nodeTarget,
        nodeHash,
        ImmutableSet.of(Paths.get("foo/FooLib.java")),
        dep);
    return TargetGraphFactory.newInstance(node, dep);
  }

  @Test
  public void hashChangesForDependentNodeWhenDepsChange() throws IOException, InterruptedException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    TestCellBuilder cellBuilder = new TestCellBuilder()
      .setFilesystem(projectFilesystem);

    BuildTarget nodeTarget = BuildTargetFactory.newInstance("//foo:lib");
    BuildTarget depTarget = BuildTargetFactory.newInstance("//dep:lib");

    TargetGraph targetGraphA = createGraphWithANodeAndADep(
        nodeTarget,
        HashCode.fromLong(12345),
        depTarget,
        HashCode.fromLong(64738));

    TargetGraph targetGraphB = createGraphWithANodeAndADep(
        nodeTarget,
        HashCode.fromLong(12345),
        depTarget,
        HashCode.fromLong(84552));

    FileHashCache fileHashCache = new FakeFileHashCache(
        ImmutableMap.of(
            projectFilesystem.resolve("foo/FooLib.java"), HashCode.fromString("abcdef"),
            projectFilesystem.resolve("dep/DepLib.java"), HashCode.fromString("123456")));

    TargetNode<?> nodeA = targetGraphA.get(nodeTarget);
    Map<TargetNode<?>, HashCode> resultA = TargetGraphHashing.hashTargetGraph(
        cellBuilder.build(),
        targetGraphA,
        fileHashCache,
        ImmutableList.<TargetNode<?>>of(nodeA));

    TargetNode<?> nodeB = targetGraphB.get(nodeTarget);
    Map<TargetNode<?>, HashCode> resultB = TargetGraphHashing.hashTargetGraph(
        cellBuilder.build(),
        targetGraphB,
        fileHashCache,
        ImmutableList.<TargetNode<?>>of(nodeB));

    TargetNode<?> depNodeA = targetGraphA.get(depTarget);
    TargetNode<?> depNodeB = targetGraphB.get(depTarget);

    assertThat(resultA, aMapWithSize(2));
    assertThat(resultA, Matchers.<TargetNode<?>>hasKey(nodeA));
    assertThat(resultA, Matchers.<TargetNode<?>>hasKey(depNodeA));
    assertThat(resultB, aMapWithSize(2));
    assertThat(resultB, Matchers.<TargetNode<?>>hasKey(nodeB));
    assertThat(resultB, Matchers.<TargetNode<?>>hasKey(depNodeB));
    assertThat(
        resultA.get(nodeA),
        not(equalTo(resultB.get(nodeB))));
    assertThat(
        resultA.get(depNodeA),
        not(equalTo(resultB.get(depNodeB))));
  }

  private static TargetNode<?> createJavaLibraryTargetNodeWithSrcs(
      BuildTarget buildTarget,
      HashCode hashCode,
      ImmutableSet<Path> srcs,
      TargetNode<?>... deps) {
    JavaLibraryBuilder targetNodeBuilder = JavaLibraryBuilder.createBuilder(buildTarget, hashCode);
    for (TargetNode<?> dep : deps) {
      targetNodeBuilder.addDep(dep.getBuildTarget());
    }
    for (Path src : srcs) {
      targetNodeBuilder.addSrc(src);
    }
    return targetNodeBuilder.build();
  }
}
