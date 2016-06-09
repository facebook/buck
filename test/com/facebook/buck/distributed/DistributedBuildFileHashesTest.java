/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;

public class DistributedBuildFileHashesTest {
  @Test
  public void recordsFileHashes() throws Exception {
    Fixture f = new Fixture() {

      @Override
      protected void setUpRules(
          BuildRuleResolver resolver,
          SourcePathResolver sourcePathResolver) throws Exception {
        Path javaSrcPath = getPath("src", "A.java");

        projectFilesystem.createParentDirs(javaSrcPath);
        projectFilesystem.writeContentsToPath("public class A {}", javaSrcPath);

        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance(projectFilesystem, "//:java_lib"), projectFilesystem)
            .addSrc(javaSrcPath)
            .build(resolver, projectFilesystem);
      }
    };

    List<BuildJobStateFileHashes> recordedHashes = f.distributedBuildFileHashes.getFileHashes();

    assertThat(recordedHashes, Matchers.hasSize(1));
    BuildJobStateFileHashes hashes = recordedHashes.get(0);
    assertThat(hashes.entries, Matchers.hasSize(1));
    BuildJobStateFileHashEntry fileHashEntry = hashes.entries.get(0);
    // It's intentional that we hardcode the path as a string here as we expect the thrift data
    // to contain unix-formated paths.
    assertThat(fileHashEntry.getPath().getPath(), Matchers.equalTo("src/A.java"));
    assertFalse(fileHashEntry.isPathIsAbsolute());
    assertFalse(fileHashEntry.isIsDirectory());
  }

  @Test
  public void recordsAbsoluteFileHashes() throws Exception {
    Fixture f = new Fixture() {
      @Override
      protected void setUpRules(
          BuildRuleResolver resolver,
          SourcePathResolver sourcePathResolver) throws Exception {
        Path hashedFileToolPath = projectFilesystem.resolve("../tool").toAbsolutePath();
        Path directoryPath = getPath("directory");
        projectFilesystem.writeContentsToPath("it's a tool, I promise", hashedFileToolPath);
        projectFilesystem.mkdirs(directoryPath);

        resolver.addToIndex(new BuildRuleWithToolAndPath(
            new FakeBuildRuleParamsBuilder("//:with_tool")
                .setProjectFilesystem(projectFilesystem)
                .build(),
            sourcePathResolver,
            new HashedFileTool(hashedFileToolPath),
            new PathSourcePath(projectFilesystem, directoryPath)
        ));
      }
    };

    List<BuildJobStateFileHashes> recordedHashes = f.distributedBuildFileHashes.getFileHashes();

    assertThat(recordedHashes, Matchers.hasSize(1));
    BuildJobStateFileHashes hashes = recordedHashes.get(0);
    assertThat(hashes.entries, Matchers.hasSize(2));

    for (BuildJobStateFileHashEntry entry : hashes.entries) {
      if (entry.getPath().getPath().toString().endsWith("tool")) {
        assertThat(entry, Matchers.notNullValue());
        assertTrue(entry.isPathIsAbsolute());
        assertFalse(entry.isIsDirectory());
      } else if (entry.getPath().equals(new PathWithUnixSeparators("directory"))) {
        assertThat(entry, Matchers.notNullValue());
        assertFalse(entry.isPathIsAbsolute());
        assertTrue(entry.isIsDirectory());
      } else {
        fail("Unknown path: " + entry.getPath().getPath());
      }
    }
  }

  @Test
  public void worksCrossCell() throws Exception {
    final Fixture f = new Fixture() {

      @Override
      protected void setUpRules(
          BuildRuleResolver resolver,
          SourcePathResolver sourcePathResolver) throws Exception {
        Path firstPath = javaFs.getPath("src", "A.java");

        projectFilesystem.createParentDirs(firstPath);
        projectFilesystem.writeContentsToPath("public class A {}", firstPath);

        Path secondPath = secondJavaFs.getPath("B.java");
        secondProjectFilesystem.writeContentsToPath("public class B {}", secondPath);

        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance(projectFilesystem, "//:java_lib"), projectFilesystem)
            .addSrc(firstPath)
            .build(resolver, projectFilesystem);

        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance(secondProjectFilesystem, "//:other_cell"),
            secondProjectFilesystem)
            .addSrc(secondPath)
            .build(resolver, secondProjectFilesystem);
      }
    };

    List<BuildJobStateFileHashes> recordedHashes = f.distributedBuildFileHashes.getFileHashes();

    assertThat(recordedHashes, Matchers.hasSize(2));
    for (BuildJobStateFileHashes hashes : recordedHashes) {
      String name = hashes.getFileSystemRootName();
      if (name.contains("first")) {
        assertThat(
            toFileHashEntryIndex(hashes),
            Matchers.hasKey(new PathWithUnixSeparators("src/A.java")));
      } else if (name.contains("second")) {
        assertThat(
            toFileHashEntryIndex(hashes),
            Matchers.hasKey(new PathWithUnixSeparators("B.java")));
      } else {
        fail("Unknown filesystem root:" + name);
      }
    }
  }

  private static ImmutableMap<PathWithUnixSeparators, BuildJobStateFileHashEntry>
  toFileHashEntryIndex(BuildJobStateFileHashes hashes) {
    return Maps.uniqueIndex(
        hashes.getEntries(),
        new Function<BuildJobStateFileHashEntry, PathWithUnixSeparators>() {
          @Override
          public PathWithUnixSeparators apply(BuildJobStateFileHashEntry input) {
            return input.getPath();
          }
        });
  }

  private abstract static class Fixture {

    protected final BuckEventBus eventBus;
    protected final ProjectFilesystem projectFilesystem;
    protected final FileSystem javaFs;

    protected final ProjectFilesystem secondProjectFilesystem;
    protected final FileSystem secondJavaFs;

    private final ActionGraph actionGraph;
    private final BuildRuleResolver buildRuleResolver;
    private final SourcePathResolver sourcePathResolver;
    private final DistributedBuildFileHashes distributedBuildFileHashes;

    public Fixture() throws Exception {
      eventBus = BuckEventBusFactory.newInstance();
      projectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/first");
      javaFs = projectFilesystem.getRootPath().getFileSystem();

      secondProjectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/second");
      secondJavaFs = secondProjectFilesystem.getRootPath().getFileSystem();

      buildRuleResolver = new BuildRuleResolver(
          TargetGraph.EMPTY,
          new DefaultTargetNodeToBuildRuleTransformer());
      sourcePathResolver = new SourcePathResolver(buildRuleResolver);
      setUpRules(buildRuleResolver, sourcePathResolver);
      actionGraph = new ActionGraph(buildRuleResolver.getBuildRules());

      distributedBuildFileHashes = new DistributedBuildFileHashes(
          actionGraph,
          sourcePathResolver,
          createFileHashCache(),
          MoreExecutors.newDirectExecutorService(),
          /* keySeed */ 0);
    }

    protected abstract void setUpRules(
        BuildRuleResolver resolver,
        SourcePathResolver sourcePathResolver) throws Exception;

    private FileHashCache createFileHashCache() {
      ImmutableList.Builder<FileHashCache> cacheList = ImmutableList.builder();
      for (Path path : javaFs.getRootDirectories()) {
        cacheList.add(new DefaultFileHashCache(new ProjectFilesystem(path)));
      }
      cacheList.add(new DefaultFileHashCache(projectFilesystem));
      cacheList.add(new DefaultFileHashCache(secondProjectFilesystem));
      return new StackedFileHashCache(cacheList.build());
    }

    public Path getPath(String first, String... more) {
      return javaFs.getPath(first, more);
    }
  }

  private static class BuildRuleWithToolAndPath extends NoopBuildRule {

    @AddToRuleKey
    Tool tool;

    @AddToRuleKey
    SourcePath sourcePath;

    public BuildRuleWithToolAndPath(
        BuildRuleParams params,
        SourcePathResolver resolver,
        Tool tool,
        SourcePath sourcePath) {
      super(params, resolver);
      this.tool = tool;
      this.sourcePath = sourcePath;
    }
  }
}
