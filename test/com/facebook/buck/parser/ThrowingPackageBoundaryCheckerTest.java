/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.InMemoryBuildFileTree;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ThrowingPackageBoundaryCheckerTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testEnforceFailsWhenPathReferencesParentDirectory() {
    LoadingCache<Cell, BuildFileTree> buildFileTrees =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Cell, BuildFileTree>() {
                  @Override
                  public BuildFileTree load(Cell cell) {
                    return new InMemoryBuildFileTree(Collections.<Path>emptyList());
                  }
                });
    ThrowingPackageBoundaryChecker boundaryChecker =
        new ThrowingPackageBoundaryChecker(buildFileTrees);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "'"
            + MorePaths.pathWithPlatformSeparators("../Test.java")
            + "' in '//a/b:c' refers to a parent directory.");

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().build(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(Paths.get("a/Test.java")));
  }

  @Test
  public void testEnforceSkippedWhenNotConfigured() {
    LoadingCache<Cell, BuildFileTree> buildFileTrees =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Cell, BuildFileTree>() {
                  @Override
                  public BuildFileTree load(Cell cell) {
                    return new InMemoryBuildFileTree(Collections.<Path>emptyList());
                  }
                });
    ThrowingPackageBoundaryChecker boundaryChecker =
        new ThrowingPackageBoundaryChecker(buildFileTrees);

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder()
            .setBuckConfig(
                FakeBuckConfig.builder()
                    .setSections("[project]", "check_package_boundary = false")
                    .build())
            .build(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(Paths.get("a/Test.java")));
  }

  @Test
  public void testEnforceFailsWhenPathDoesntBelongToPackage() {
    LoadingCache<Cell, BuildFileTree> buildFileTrees =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Cell, BuildFileTree>() {
                  @Override
                  public BuildFileTree load(Cell cell) {
                    return new InMemoryBuildFileTree(Collections.<Path>emptyList()) {
                      @Override
                      public Optional<Path> getBasePathOfAncestorTarget(Path filePath) {
                        return Optional.empty();
                      }
                    };
                  }
                });
    ThrowingPackageBoundaryChecker boundaryChecker =
        new ThrowingPackageBoundaryChecker(buildFileTrees);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage(
        "Target '//a/b:c' refers to file '"
            + MorePaths.pathWithPlatformSeparators("a/b/Test.java")
            + "', which doesn't belong to any package");

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().build(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(Paths.get("a/b/Test.java")));
  }

  @Test
  public void testEnforceFailsWhenAncestorNotEqualsToBasePath() {
    LoadingCache<Cell, BuildFileTree> buildFileTrees =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Cell, BuildFileTree>() {
                  @Override
                  public BuildFileTree load(Cell cell) {
                    return new InMemoryBuildFileTree(Collections.<Path>emptyList()) {
                      @Override
                      public Optional<Path> getBasePathOfAncestorTarget(Path filePath) {
                        return Optional.of(Paths.get("d"));
                      }
                    };
                  }
                });
    ThrowingPackageBoundaryChecker boundaryChecker =
        new ThrowingPackageBoundaryChecker(buildFileTrees);

    thrown.expect(HumanReadableException.class);
    String testPath = MorePaths.pathWithPlatformSeparators("a/b/Test.java");
    String dPath = MorePaths.pathWithPlatformSeparators("d/BUCK");
    thrown.expectMessage(
        "The target '//a/b:c' tried to reference '"
            + testPath
            + "'.\nThis is not allowed because '"
            + testPath
            + "' can only be referenced from '"
            + dPath
            + "' \nwhich is its closest parent 'BUCK' file.\n\n"
            + "You should find or create the rule in '"
            + dPath
            + "' that references\n'"
            + testPath
            + "' and use that in '//a/b:c'\ninstead of directly referencing '"
            + testPath
            + "'.\n\nThis may also be due to a bug in buckd's caching.\n"
            + "Please check whether using `buck kill` will resolve it.");

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().build(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(Paths.get("a/b/Test.java")));
  }

  @Test
  public void testEnforceDoesntFailWhenPathsAreValid() {
    LoadingCache<Cell, BuildFileTree> buildFileTrees =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<Cell, BuildFileTree>() {
                  @Override
                  public BuildFileTree load(Cell cell) {
                    return new InMemoryBuildFileTree(Collections.singleton(Paths.get("a/b")));
                  }
                });
    ThrowingPackageBoundaryChecker boundaryChecker =
        new ThrowingPackageBoundaryChecker(buildFileTrees);

    boundaryChecker.enforceBuckPackageBoundaries(
        new TestCellBuilder().setFilesystem(new FakeProjectFilesystem()).build(),
        BuildTargetFactory.newInstance("//a/b:c"),
        ImmutableSet.of(Paths.get("a/b/Test.java")));
  }
}
