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

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Unit tests for {@link TargetGraphHashing}.
 */
public class TargetGraphHashingTest {

  @Test
  public void emptyTargetGraphHasEmptyHashes() throws IOException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    TargetGraph targetGraph = TargetGraphFactory.newInstance();

    assertThat(
        TargetGraphHashing.hashTargetGraph(
            projectFilesystem,
            targetGraph,
            Functions.forMap(ImmutableMap.<BuildTarget, HashCode>of()))
            .entrySet(),
        empty());
  }

  @Test
  public void oneNodeTargetGraphHasExpectedHash() throws IOException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    TargetNode<?> node = createJavaLibraryTargetNodeWithSrcs(
        projectFilesystem,
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableMap.of(Paths.get("foo/FooLib.java"), "Hello world"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(node);

    ImmutableMap<BuildTarget, HashCode> expectedTargetHashes = ImmutableMap.of(
        (BuildTarget) BuildTarget.builder("//foo", "lib").build(),
        HashCode.fromString("15bcb369ef1f2c6df995f0538b7d95f4798a16e7")
    );

    assertThat(
        TargetGraphHashing.hashTargetGraph(
            projectFilesystem,
            targetGraph,
            Functions.forMap(
                ImmutableMap.of(
                    (BuildTarget) BuildTarget.builder("//foo", "lib").build(),
                    HashCode.fromLong(64738))),
            node.getBuildTarget()),
        equalTo(expectedTargetHashes));
  }

  @Test
  public void hashChangesWhenSrcContentChanges() throws IOException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    TargetNode<?> node = createJavaLibraryTargetNodeWithSrcs(
        projectFilesystem,
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableMap.of(Paths.get("foo/FooLib.java"), "Goodbye world"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(node);

    ImmutableMap<BuildTarget, HashCode> expectedTargetHashes = ImmutableMap.of(
        (BuildTarget) BuildTarget.builder("//foo", "lib").build(),
        HashCode.fromString("6cb8ac509e591d722c2902a08be8738649a620b9")
    );

    assertThat(
        TargetGraphHashing.hashTargetGraph(
            projectFilesystem,
            targetGraph,
            Functions.forMap(
                ImmutableMap.of(
                    (BuildTarget) BuildTarget.builder("//foo", "lib").build(),
                    HashCode.fromLong(64738))),
            node.getBuildTarget()),
        equalTo(expectedTargetHashes));
  }

  @Test
  public void twoNodeIndependentRootsTargetGraphHasExpectedHashes() throws IOException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    TargetNode<?> nodeA = createJavaLibraryTargetNodeWithSrcs(
        projectFilesystem,
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableMap.of(Paths.get("foo/FooLib.java"), "Hello world"));
    TargetNode<?> nodeB = createJavaLibraryTargetNodeWithSrcs(
        projectFilesystem,
        BuildTarget.builder("//bar", "lib").build(),
        ImmutableMap.of(Paths.get("bar/BarLib.java"), "Hello world"));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodeA, nodeB);

    ImmutableMap<BuildTarget, HashCode> expectedTargetHashes = ImmutableMap.of(
        (BuildTarget) BuildTarget.builder("//foo", "lib").build(),
        HashCode.fromString("15bcb369ef1f2c6df995f0538b7d95f4798a16e7"),
        BuildTarget.builder("//bar", "lib").build(),
        HashCode.fromString("55f239830db5afd6ee64574eb974e0ec058bb8f8")
    );

    assertThat(
        TargetGraphHashing.hashTargetGraph(
            projectFilesystem,
            targetGraph,
            Functions.forMap(
                ImmutableMap.of(
                    (BuildTarget) BuildTarget.builder("//foo", "lib").build(),
                    HashCode.fromLong(64738),
                    BuildTarget.builder("//bar", "lib").build(),
                    HashCode.fromLong(49152))),
            nodeA.getBuildTarget(),
            nodeB.getBuildTarget()),
        equalTo(expectedTargetHashes));
  }

  @Test
  public void hashChangesForDependentNodeWhenDepsChange() throws IOException {
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    TargetNode<?> nodeA = createJavaLibraryTargetNodeWithSrcs(
        projectFilesystem,
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableMap.of(Paths.get("foo/FooLib.java"), "Hello world"));
    // Same as twoNodeIndependentRootsTargetGraphHasExpectedHashes, except
    // now nodeB depends on nodeA.
    TargetNode<?> nodeB = createJavaLibraryTargetNodeWithSrcs(
        projectFilesystem,
        BuildTarget.builder("//bar", "lib").build(),
        ImmutableMap.of(Paths.get("bar/BarLib.java"), "Hello world"),
        nodeA);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(nodeA, nodeB);

    ImmutableMap<BuildTarget, HashCode> expectedTargetHashes = ImmutableMap.of(
        (BuildTarget) BuildTarget.builder("//foo", "lib").build(),
        HashCode.fromString("15bcb369ef1f2c6df995f0538b7d95f4798a16e7"),
        BuildTarget.builder("//bar", "lib").build(),
        HashCode.fromString("f5441f7d7c2ece03c68bc3c620496dc1195a5a32")
    );

    assertThat(
        TargetGraphHashing.hashTargetGraph(
            projectFilesystem,
            targetGraph,
            Functions.forMap(
                ImmutableMap.of(
                    (BuildTarget) BuildTarget.builder("//foo", "lib").build(),
                    HashCode.fromLong(64738),
                    BuildTarget.builder("//bar", "lib").build(),
                    HashCode.fromLong(49152))),
            nodeB.getBuildTarget()),
        equalTo(expectedTargetHashes));
  }

  private static TargetNode<?> createJavaLibraryTargetNodeWithSrcs(
      FakeProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      ImmutableMap<Path, String> srcs,
      TargetNode<?>... deps) throws IOException {
    JavaLibraryBuilder targetNodeBuilder = JavaLibraryBuilder.createBuilder(buildTarget);
    for (TargetNode<?> dep : deps) {
      targetNodeBuilder.addDep(dep.getBuildTarget());
    }
    for (ImmutableMap.Entry<Path, String> entry : srcs.entrySet()) {
      projectFilesystem.writeContentsToPath(entry.getValue(), entry.getKey());
      targetNodeBuilder.addSrc(entry.getKey());
    }
    return targetNodeBuilder.build();
  }
}
