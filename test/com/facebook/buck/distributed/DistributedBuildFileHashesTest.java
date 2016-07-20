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
import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.HashingDeterministicJarWriter;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.ArchiveMemberSourcePath;
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
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;

public class DistributedBuildFileHashesTest {


  private static class SingleFileFixture extends Fixture {
    protected Path javaSrcPath;
    protected HashCode writtenHashCode;
    protected String writtenContents;

    public SingleFileFixture() throws Exception {
    }

    @Override
    protected void setUpRules(
        BuildRuleResolver resolver,
        SourcePathResolver sourcePathResolver) throws Exception {
      javaSrcPath = getPath("src", "A.java");

      projectFilesystem.createParentDirs(javaSrcPath);
      writtenContents = "public class A {}";
      projectFilesystem.writeContentsToPath(writtenContents, javaSrcPath);
      writtenHashCode = HashCode.fromString(projectFilesystem.computeSha1(javaSrcPath));

      JavaLibraryBuilder.createBuilder(
          BuildTargetFactory.newInstance(projectFilesystem, "//:java_lib"), projectFilesystem)
          .addSrc(javaSrcPath)
          .build(resolver, projectFilesystem);
    }
  }

  @Test
  public void recordsFileHashes() throws Exception {
    SingleFileFixture f = new SingleFileFixture();

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
  public void cacheReadsHashesForFiles() throws Exception {
    SingleFileFixture f = new SingleFileFixture();

    List<BuildJobStateFileHashes> fileHashes = f.distributedBuildFileHashes.getFileHashes();
    f.projectFilesystem.getRootPath().getFileSystem().close();
    f.secondProjectFilesystem.getRootPath().getFileSystem().close();

    ProjectFilesystem readProjectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem(
        "/read_hashes");
    FileHashCache fileHashCache = DistributedBuildFileHashes.createFileHashCache(
        readProjectFilesystem,
        fileHashes.get(0));

    assertThat(
        fileHashCache.willGet(readProjectFilesystem.resolve(f.javaSrcPath)),
        Matchers.equalTo(true));

    assertThat(
        fileHashCache.get(readProjectFilesystem.resolve(f.javaSrcPath)),
        Matchers.equalTo(f.writtenHashCode));
  }

  @Test
  public void materializerWritesContents() throws Exception {
    SingleFileFixture f = new SingleFileFixture();

    List<BuildJobStateFileHashes> fileHashes = f.distributedBuildFileHashes.getFileHashes();
    f.projectFilesystem.getRootPath().getFileSystem().close();
    f.secondProjectFilesystem.getRootPath().getFileSystem().close();

    ProjectFilesystem materializeProjectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem(
        "/read_hashes");
    FileHashLoader materializer = DistributedBuildFileHashes.createMaterializingLoader(
        materializeProjectFilesystem,
        fileHashes.get(0));

    materializer.get(materializeProjectFilesystem.resolve(f.javaSrcPath));
    assertThat(
        materializeProjectFilesystem.readFileIfItExists(f.javaSrcPath),
        Matchers.equalTo(Optional.of(f.writtenContents)));
  }

  @Test
  public void cacheMaterializes() throws Exception {
    SingleFileFixture f = new SingleFileFixture();

    List<BuildJobStateFileHashes> fileHashes = f.distributedBuildFileHashes.getFileHashes();
    f.projectFilesystem.getRootPath().getFileSystem().close();
    f.secondProjectFilesystem.getRootPath().getFileSystem().close();

    ProjectFilesystem readProjectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem(
        "/read_hashes");
    FileHashCache fileHashCache = DistributedBuildFileHashes.createFileHashCache(
        readProjectFilesystem,
        fileHashes.get(0));

    assertThat(
        fileHashCache.willGet(readProjectFilesystem.resolve("src/A.java")),
        Matchers.equalTo(true));

    assertThat(
        fileHashCache.get(readProjectFilesystem.resolve("src/A.java")),
        Matchers.equalTo(f.writtenHashCode));
  }

  private static class ArchiveFilesFixture extends Fixture implements AutoCloseable {
    private final DebuggableTemporaryFolder firstFolder;
    private final DebuggableTemporaryFolder secondFolder;
    protected Path archivePath;
    protected Path archiveMemberPath;
    protected HashCode archiveMemberHash;

    private ArchiveFilesFixture(
        DebuggableTemporaryFolder firstFolder,
        DebuggableTemporaryFolder secondFolder) throws Exception {
      super(new ProjectFilesystem(firstFolder.getRootPath()),
          new ProjectFilesystem(secondFolder.getRootPath()));
      this.firstFolder = firstFolder;
      this.secondFolder = secondFolder;
    }

    public static ArchiveFilesFixture create() throws Exception {
      DebuggableTemporaryFolder firstFolder = null;
      DebuggableTemporaryFolder secondFolder = null;
      try {
        firstFolder = new DebuggableTemporaryFolder();
        firstFolder.create();
        secondFolder = new DebuggableTemporaryFolder();
        secondFolder.create();
        return new ArchiveFilesFixture(firstFolder, secondFolder);
      } catch (IOException e) {
        firstFolder.delete();
        if (secondFolder != null) {
          secondFolder.delete();
        }
        throw e;
      }
    }

    @Override
    protected void setUpRules(
        BuildRuleResolver resolver,
        SourcePathResolver sourcePathResolver) throws Exception {
      archivePath = getPath("src", "archive.jar");
      archiveMemberPath = getPath("Archive.class");

      projectFilesystem.createParentDirs(archivePath);
      try (HashingDeterministicJarWriter jarWriter =
               new HashingDeterministicJarWriter(
                   new JarOutputStream(
                       projectFilesystem.newFileOutputStream(archivePath)))) {
        byte[] archiveMemberData = "data".getBytes(Charsets.UTF_8);
        archiveMemberHash = Hashing.murmur3_128().hashBytes(archiveMemberData);
        jarWriter.writeEntry("Archive.class", ByteSource.wrap(archiveMemberData));
      }

      resolver.addToIndex(new BuildRuleWithToolAndPath(
          new FakeBuildRuleParamsBuilder("//:with_tool")
              .setProjectFilesystem(projectFilesystem)
              .build(),
          sourcePathResolver,
          null,
          new ArchiveMemberSourcePath(
              new PathSourcePath(projectFilesystem, archivePath),
              archiveMemberPath)));
    }

    @Override
    public void close() throws Exception {
      firstFolder.delete();
      secondFolder.delete();
    }
  }

  @Test
  public void recordsArchiveHashes() throws Exception {
    try (ArchiveFilesFixture f = ArchiveFilesFixture.create()) {
      List<BuildJobStateFileHashes> recordedHashes = f.distributedBuildFileHashes.getFileHashes();

      assertThat(recordedHashes, Matchers.hasSize(1));
      BuildJobStateFileHashes hashes = recordedHashes.get(0);
      assertThat(hashes.entries, Matchers.hasSize(1));
      BuildJobStateFileHashEntry fileHashEntry = hashes.entries.get(0);
      assertThat(fileHashEntry.getPath().getPath(), Matchers.equalTo("src/archive.jar"));
      assertTrue(fileHashEntry.isSetArchiveMemberPath());
      assertThat(fileHashEntry.getArchiveMemberPath(), Matchers.equalTo("Archive.class"));
      assertFalse(fileHashEntry.isPathIsAbsolute());
      assertFalse(fileHashEntry.isIsDirectory());
    }
  }

  @Test
  public void readsHashesForArchiveMembers() throws Exception {
    try (ArchiveFilesFixture f = ArchiveFilesFixture.create()) {
      List<BuildJobStateFileHashes> recordedHashes = f.distributedBuildFileHashes.getFileHashes();

      ProjectFilesystem readProjectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem(
          "/read_hashes");
      FileHashCache fileHashCache = DistributedBuildFileHashes.createFileHashCache(
          readProjectFilesystem,
          recordedHashes.get(0));

      ArchiveMemberPath archiveMemberPath = ArchiveMemberPath.of(
          readProjectFilesystem.resolve(f.archivePath),
          f.archiveMemberPath);
      assertThat(
          fileHashCache.willGet(archiveMemberPath),
          Matchers.is(true));
      assertThat(
          fileHashCache.get(archiveMemberPath),
          Matchers.is(f.archiveMemberHash));
    }
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
    ImmutableBiMap<Path, Integer> cellIndex = f.cellIndexer.getIndex();

    assertThat(recordedHashes, Matchers.hasSize(2));
    for (BuildJobStateFileHashes hashes : recordedHashes) {
      Path cellPath = cellIndex.inverse().get(hashes.getCellIndex());
      if (cellPath.equals(f.projectFilesystem.getRootPath())) {
        assertThat(
            toFileHashEntryIndex(hashes),
            Matchers.hasKey(new PathWithUnixSeparators("src/A.java")));
      } else if (cellPath.equals(f.secondProjectFilesystem.getRootPath())) {
        assertThat(
            toFileHashEntryIndex(hashes),
            Matchers.hasKey(new PathWithUnixSeparators("B.java")));
      } else {
        fail("Unknown filesystem root:" + cellPath);
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

  private static class FakeIndexer implements Function<Path, Integer> {
    private final Map<Path, Integer> cache;

    private FakeIndexer() {
      cache = new HashMap<>();
    }

    public ImmutableBiMap<Path, Integer> getIndex() {
      return ImmutableBiMap.copyOf(cache);
    }

    @Override
    public Integer apply(Path input) {
      Integer result = cache.get(input);
      if (result == null) {
        result = cache.size();
        cache.put(input, result);
      }
      return result;
    }
  }

  private abstract static class Fixture {

    protected final BuckEventBus eventBus;
    protected final ProjectFilesystem projectFilesystem;
    protected final FileSystem javaFs;

    protected final ProjectFilesystem secondProjectFilesystem;
    protected final FileSystem secondJavaFs;

    protected final ActionGraph actionGraph;
    protected final BuildRuleResolver buildRuleResolver;
    protected final SourcePathResolver sourcePathResolver;
    protected final FakeIndexer cellIndexer;
    protected final DistributedBuildFileHashes distributedBuildFileHashes;

    public Fixture(ProjectFilesystem first, ProjectFilesystem second) throws Exception {
      eventBus = BuckEventBusFactory.newInstance();
      projectFilesystem = first;
      javaFs = projectFilesystem.getRootPath().getFileSystem();

      secondProjectFilesystem = second;
      secondJavaFs = secondProjectFilesystem.getRootPath().getFileSystem();

      buildRuleResolver = new BuildRuleResolver(
          TargetGraph.EMPTY,
          new DefaultTargetNodeToBuildRuleTransformer());
      sourcePathResolver = new SourcePathResolver(buildRuleResolver);
      setUpRules(buildRuleResolver, sourcePathResolver);
      actionGraph = new ActionGraph(buildRuleResolver.getBuildRules());
      cellIndexer = new FakeIndexer();

      distributedBuildFileHashes = new DistributedBuildFileHashes(
          actionGraph,
          sourcePathResolver,
          createFileHashCache(),
          cellIndexer,
          MoreExecutors.newDirectExecutorService(),
          /* keySeed */ 0);
    }

    public Fixture() throws Exception {
      this(FakeProjectFilesystem.createJavaOnlyFilesystem("/first"),
          FakeProjectFilesystem.createJavaOnlyFilesystem("/second"));
    }

    protected abstract void setUpRules(
        BuildRuleResolver resolver,
        SourcePathResolver sourcePathResolver) throws Exception;

    private FileHashCache createFileHashCache() {
      ImmutableList.Builder<FileHashCache> cacheList = ImmutableList.builder();
      for (Path path : javaFs.getRootDirectories()) {
        if (Files.isDirectory(path)) {
          cacheList.add(
              DefaultFileHashCache.createDefaultFileHashCache(new ProjectFilesystem(path)));
        }
      }
      cacheList.add(DefaultFileHashCache.createDefaultFileHashCache(projectFilesystem));
      cacheList.add(DefaultFileHashCache.createDefaultFileHashCache(secondProjectFilesystem));
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
