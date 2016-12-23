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

import com.facebook.buck.cli.NoOpConfigPathGetter;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.HashingDeterministicJarWriter;
import com.facebook.buck.io.MoreFiles;
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
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.MoreExecutors;

import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarOutputStream;

public class DistributedBuildFileHashesTest {
  @Rule
  public TemporaryFolder tempDir = new TemporaryFolder();

  @Rule
  public TemporaryFolder archiveTempDir = new TemporaryFolder();

  private static class SingleFileFixture extends Fixture {
    protected Path javaSrcPath;
    protected HashCode writtenHashCode;
    protected String writtenContents;

    public SingleFileFixture(TemporaryFolder tempDir) throws Exception {
      super(tempDir);
    }

    @Override
    protected void setUpRules(
        BuildRuleResolver resolver,
        SourcePathResolver sourcePathResolver) throws Exception {
      javaSrcPath = getPath("src", "A.java");

      projectFilesystem.createParentDirs(javaSrcPath);
      writtenContents = "public class A {}";
      projectFilesystem.writeContentsToPath(writtenContents, javaSrcPath);
      writtenHashCode = Hashing.sha1().hashString(writtenContents, Charsets.UTF_8);

      JavaLibraryBuilder.createBuilder(
          BuildTargetFactory.newInstance(projectFilesystem, "//:java_lib"), projectFilesystem)
          .addSrc(javaSrcPath)
          .build(resolver, projectFilesystem);
    }
  }

  @Test
  public void recordsFileHashes() throws Exception {
    SingleFileFixture f = new SingleFileFixture(tempDir);

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
    SingleFileFixture f = new SingleFileFixture(tempDir);

    List<BuildJobStateFileHashes> fileHashes = f.distributedBuildFileHashes.getFileHashes();

    ProjectFilesystem readProjectFilesystem =
        new ProjectFilesystem(tempDir.newFolder("read_hashes").toPath().toRealPath());
    FileHashCache fileHashCache = DistBuildFileHashes.createFileHashCache(
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
    SingleFileFixture f = new SingleFileFixture(tempDir);

    List<BuildJobStateFileHashes> fileHashes = f.distributedBuildFileHashes.getFileHashes();

    ProjectFilesystem materializeProjectFilesystem =
        new ProjectFilesystem(tempDir.newFolder("read_hashes").getCanonicalFile().toPath());

    FileHashCache fileHashLoader = EasyMock.createMock(FileHashCache.class);
    DistBuildFileMaterializer materializer =
        new DistBuildFileMaterializer(
            materializeProjectFilesystem,
            fileHashes.get(0),
            new FileContentsProviders.InlineContentsProvider(),
            fileHashLoader);

    materializer.get(materializeProjectFilesystem.resolve(f.javaSrcPath));
    assertThat(
        materializeProjectFilesystem.readFileIfItExists(f.javaSrcPath),
        Matchers.equalTo(Optional.of(f.writtenContents)));
  }

  @Test
  public void cacheMaterializes() throws Exception {
    SingleFileFixture f = new SingleFileFixture(tempDir);

    List<BuildJobStateFileHashes> fileHashes = f.distributedBuildFileHashes.getFileHashes();

    ProjectFilesystem readProjectFilesystem =
        new ProjectFilesystem(tempDir.newFolder("read_hashes").toPath().toRealPath());
    FileHashCache fileHashCache = DistBuildFileHashes.createFileHashCache(
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
    private final Path firstFolder;
    private final Path secondFolder;
    protected Path archivePath;
    protected Path archiveMemberPath;
    protected HashCode archiveMemberHash;

    private ArchiveFilesFixture(
        Path firstFolder,
        Path secondFolder) throws Exception {
      super(
          new ProjectFilesystem(firstFolder),
          new ProjectFilesystem(secondFolder));
      this.firstFolder = firstFolder;
      this.secondFolder = secondFolder;
    }

    public static ArchiveFilesFixture create(TemporaryFolder archiveTempDir) throws Exception {
      return new ArchiveFilesFixture(
          archiveTempDir.newFolder("first").toPath().toRealPath(),
          archiveTempDir.newFolder("second").toPath().toRealPath());
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
        jarWriter.writeEntry("Archive.class", new ByteArrayInputStream(archiveMemberData));
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
      MoreFiles.deleteRecursively(firstFolder);
      MoreFiles.deleteRecursively(secondFolder);
    }
  }

  @Test
  public void recordsArchiveHashes() throws Exception {
    try (ArchiveFilesFixture f = ArchiveFilesFixture.create(archiveTempDir)) {
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
    try (ArchiveFilesFixture f = ArchiveFilesFixture.create(archiveTempDir)) {
      List<BuildJobStateFileHashes> recordedHashes = f.distributedBuildFileHashes.getFileHashes();

      ProjectFilesystem readProjectFilesystem =
          new ProjectFilesystem(tempDir.newFolder("read_hashes").toPath().toRealPath());
      FileHashCache fileHashCache = DistBuildFileHashes.createFileHashCache(
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
    Fixture f = new Fixture(tempDir) {
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
    final Fixture f = new Fixture(tempDir) {

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
        BuildJobStateFileHashEntry::getPath);
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

    protected final ProjectFilesystem projectFilesystem;
    protected final FileSystem javaFs;

    protected final ProjectFilesystem secondProjectFilesystem;
    protected final FileSystem secondJavaFs;

    protected final ActionGraph actionGraph;
    protected final BuildRuleResolver buildRuleResolver;
    protected final SourcePathRuleFinder ruleFinder;
    protected final SourcePathResolver sourcePathResolver;
    protected final FakeIndexer cellIndexer;
    protected final DistBuildFileHashes distributedBuildFileHashes;

    public Fixture(ProjectFilesystem first, ProjectFilesystem second) throws Exception {
      projectFilesystem = first;
      javaFs = projectFilesystem.getRootPath().getFileSystem();

      secondProjectFilesystem = second;
      secondJavaFs = secondProjectFilesystem.getRootPath().getFileSystem();

      buildRuleResolver = new BuildRuleResolver(
          TargetGraph.EMPTY,
          new DefaultTargetNodeToBuildRuleTransformer());
      ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
      sourcePathResolver = new SourcePathResolver(ruleFinder);
      setUpRules(buildRuleResolver, sourcePathResolver);
      actionGraph = new ActionGraph(buildRuleResolver.getBuildRules());
      cellIndexer = new FakeIndexer();

      distributedBuildFileHashes = new DistBuildFileHashes(
          actionGraph,
          sourcePathResolver,
          ruleFinder,
          createFileHashCache(),
          cellIndexer,
          MoreExecutors.newDirectExecutorService(),
          /* keySeed */ 0,
          new NoOpConfigPathGetter());
    }

    public Fixture(TemporaryFolder tempDir) throws Exception {
      this(new ProjectFilesystem(tempDir.newFolder("first").toPath().toRealPath()),
          new ProjectFilesystem(tempDir.newFolder("second").toPath().toRealPath()));
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
