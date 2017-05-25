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

package com.facebook.buck.distributed;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MaterializerProjectFileHashCacheTest {
  @Rule public TemporaryFolder projectDir = new TemporaryFolder();

  @Rule public TemporaryFolder externalDir = new TemporaryFolder();

  private static final HashCode EXAMPLE_HASHCODE = HashCode.fromString("1234");
  private static final HashCode EXAMPLE_HASHCODE_TWO = HashCode.fromString("3456");
  private static final String FILE_CONTENTS = "filecontents";
  private static final String FILE_CONTENTS_TWO = "filecontentstwo";

  interface MaterializeFunction {
    void execute(MaterializerProjectFileHashCache materializer, Path path) throws IOException;
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  private static final MaterializeFunction THROWING_GET =
      (materializer, path) -> {
        try {
          materializer.get(path);
          Assert.fail("Materialization should have thrown because of mismatching hash.");
        } catch (RuntimeException e) {
          // expected.
        }
      };

  private static final MaterializeFunction GET = (materializer, path) -> materializer.get(path);
  private static final MaterializeFunction PRELOAD =
      (materializer, path) -> materializer.preloadAllFiles();
  private static final MaterializeFunction PRELOAD_THEN_GET =
      (materializer, path) -> {
        materializer.preloadAllFiles();
        materializer.get(path);
      };

  private void testMaterializeDirectoryHelper(
      boolean materializeDuringPreloading,
      MaterializeFunction materializeFunction,
      boolean setCorrectHashCode)
      throws InterruptedException, IOException {
    // Scenario:
    // file hash entries for:
    // /a - folder
    // /a/b - folder
    // /a/b/c - file
    // /a/b/d - folder
    // /a/e - file
    // => preload: ensure all folders created and files touched
    // => materialize(/a): ensure all folders and sub-directories/files created

    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());
    Path pathDirA = projectFilesystem.resolve("a");
    Path pathDirAb = projectFilesystem.resolve("a/b");
    Path pathFileAbc = projectFilesystem.resolve("a/b/c");
    Path pathDirAbd = projectFilesystem.resolve("a/b/d");
    Path pathFileAe = projectFilesystem.resolve("a/e");

    Path relativePathDirA = Paths.get("a");
    Path relativePathDirAb = Paths.get("a/b");
    Path relativePathFileAbc = Paths.get("a/b/c");
    Path relativePathDirAbd = Paths.get("a/b/d");
    Path relativePathFileAe = Paths.get("a/e");

    ProjectFileHashCache mockFileHashCache = EasyMock.createNiceMock(ProjectFileHashCache.class);
    expect(mockFileHashCache.getFilesystem()).andReturn(projectFilesystem).atLeastOnce();

    BuildJobStateFileHashes fileHashes = new BuildJobStateFileHashes();
    BuildJobStateFileHashEntry dirAFileHashEntry = new BuildJobStateFileHashEntry();
    dirAFileHashEntry.setPath(unixPath(relativePathDirA));
    dirAFileHashEntry.setHashCode("aa");
    dirAFileHashEntry.setIsDirectory(true);
    dirAFileHashEntry.setChildren(
        ImmutableList.of(unixPath(relativePathDirAb), unixPath(relativePathFileAe)));
    dirAFileHashEntry.setMaterializeDuringPreloading(materializeDuringPreloading);
    fileHashes.addToEntries(dirAFileHashEntry);
    expect(mockFileHashCache.get(relativePathDirA))
        .andReturn(setCorrectHashCode ? HashCode.fromString("aa") : EXAMPLE_HASHCODE)
        .atLeastOnce();

    BuildJobStateFileHashEntry dirAbFileHashEntry = new BuildJobStateFileHashEntry();
    dirAbFileHashEntry.setPath(unixPath(relativePathDirAb));
    dirAbFileHashEntry.setHashCode("abab");
    dirAbFileHashEntry.setIsDirectory(true);
    dirAbFileHashEntry.setChildren(
        ImmutableList.of(unixPath(relativePathFileAbc), unixPath(relativePathDirAbd)));
    dirAbFileHashEntry.setMaterializeDuringPreloading(materializeDuringPreloading);
    fileHashes.addToEntries(dirAbFileHashEntry);
    expect(mockFileHashCache.get(relativePathDirAb))
        .andReturn(setCorrectHashCode ? HashCode.fromString("abab") : EXAMPLE_HASHCODE)
        .atLeastOnce();

    BuildJobStateFileHashEntry fileAbcFileHashEntry = new BuildJobStateFileHashEntry();
    fileAbcFileHashEntry.setPath(unixPath(relativePathFileAbc));
    fileAbcFileHashEntry.setHashCode("abcabc");
    fileAbcFileHashEntry.setContents(FILE_CONTENTS.getBytes(StandardCharsets.UTF_8));
    fileAbcFileHashEntry.setIsDirectory(false);
    fileAbcFileHashEntry.setMaterializeDuringPreloading(materializeDuringPreloading);
    fileHashes.addToEntries(fileAbcFileHashEntry);
    expect(mockFileHashCache.get(relativePathFileAbc))
        .andReturn(setCorrectHashCode ? HashCode.fromString("abcabc") : EXAMPLE_HASHCODE)
        .atLeastOnce();

    BuildJobStateFileHashEntry dirAbdFileHashEntry = new BuildJobStateFileHashEntry();
    dirAbdFileHashEntry.setPath(unixPath(relativePathDirAbd));
    dirAbdFileHashEntry.setHashCode("abdabd");
    dirAbdFileHashEntry.setIsDirectory(true);
    dirAbdFileHashEntry.setChildren(ImmutableList.of());
    dirAbdFileHashEntry.setMaterializeDuringPreloading(materializeDuringPreloading);
    fileHashes.addToEntries(dirAbdFileHashEntry);
    expect(mockFileHashCache.get(relativePathDirAbd))
        .andReturn(setCorrectHashCode ? HashCode.fromString("abdabd") : EXAMPLE_HASHCODE)
        .atLeastOnce();

    BuildJobStateFileHashEntry fileAeFileHashEntry = new BuildJobStateFileHashEntry();
    fileAeFileHashEntry.setPath(unixPath(relativePathFileAe));
    fileAeFileHashEntry.setHashCode("aeae");
    fileAeFileHashEntry.setContents(FILE_CONTENTS_TWO.getBytes(StandardCharsets.UTF_8));
    fileAeFileHashEntry.setIsDirectory(false);
    fileAeFileHashEntry.setMaterializeDuringPreloading(materializeDuringPreloading);
    fileHashes.addToEntries(fileAeFileHashEntry);
    expect(mockFileHashCache.get(relativePathFileAe))
        .andReturn(setCorrectHashCode ? HashCode.fromString("aeae") : EXAMPLE_HASHCODE)
        .atLeastOnce();

    InlineContentsProvider inlineProvider = new InlineContentsProvider();
    replay(mockFileHashCache);

    MaterializerProjectFileHashCache fileMaterializer =
        new MaterializerProjectFileHashCache(mockFileHashCache, fileHashes, inlineProvider);

    assertFalse(pathDirA.toFile().exists());
    assertFalse(pathDirAb.toFile().exists());
    assertFalse(pathFileAbc.toFile().exists());
    assertFalse(pathDirAbd.toFile().exists());
    assertFalse(pathFileAe.toFile().exists());

    materializeFunction.execute(fileMaterializer, relativePathDirA);

    if (setCorrectHashCode) {
      // If we set the wrong hash, we may or may not have materialized everything.
      assertTrue(pathDirA.toFile().exists());
      assertTrue(pathDirAb.toFile().exists());
      assertTrue(pathFileAbc.toFile().exists());
      assertTrue(pathDirAbd.toFile().exists());
      assertTrue(pathFileAe.toFile().exists());
    }
  }

  @Test
  public void testMaterializeDirectory() throws InterruptedException, IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());

    testMaterializeDirectoryHelper(false, GET, true);

    String fileAbcContents = new String(Files.readAllBytes(projectFilesystem.resolve("a/b/c")));
    assertThat(fileAbcContents, Matchers.equalTo(FILE_CONTENTS));

    String fileAeContents = new String(Files.readAllBytes(projectFilesystem.resolve("a/e")));
    assertThat(fileAeContents, Matchers.equalTo(FILE_CONTENTS_TWO));
  }

  @Test
  public void testMaterializeDuringPreloadingDirectory() throws InterruptedException, IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());

    testMaterializeDirectoryHelper(true, PRELOAD, true);

    String fileAbcContents = new String(Files.readAllBytes(projectFilesystem.resolve("a/b/c")));
    assertThat(fileAbcContents, Matchers.equalTo(FILE_CONTENTS));

    String fileAeContents = new String(Files.readAllBytes(projectFilesystem.resolve("a/e")));
    assertThat(fileAeContents, Matchers.equalTo(FILE_CONTENTS_TWO));
  }

  @Test
  public void testPreloadDirectory() throws InterruptedException, IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());

    testMaterializeDirectoryHelper(false, PRELOAD, true);

    assertThat(projectFilesystem.resolve("a/b/c").toFile().length(), Matchers.equalTo(0L));
    assertThat(projectFilesystem.resolve("a/e").toFile().length(), Matchers.equalTo(0L));
  }

  @Test
  public void testPreloadThenMaterializeDirectory() throws InterruptedException, IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());

    testMaterializeDirectoryHelper(false, PRELOAD_THEN_GET, true);

    String fileAbcContents = new String(Files.readAllBytes(projectFilesystem.resolve("a/b/c")));
    assertThat(fileAbcContents, Matchers.equalTo(FILE_CONTENTS));

    String fileAeContents = new String(Files.readAllBytes(projectFilesystem.resolve("a/e")));
    assertThat(fileAeContents, Matchers.equalTo(FILE_CONTENTS_TWO));
  }

  @Test
  public void testMaterializeDirWithDifferentHashCodeThrowsException()
      throws InterruptedException, IOException {
    testMaterializeDirectoryHelper(false, THROWING_GET, false);
  }

  private Path testEntryForRealFile(
      boolean materializeDuringPreloading,
      MaterializeFunction materializeFunction,
      boolean setCorrectHash)
      throws InterruptedException, IOException {
    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());
    Path realFileAbsPath = projectFilesystem.resolve("realfile");
    Path relativeRealFile = Paths.get("realfile");

    BuildJobStateFileHashEntry realFileHashEntry = new BuildJobStateFileHashEntry();
    realFileHashEntry.setPath(unixPath(relativeRealFile));
    realFileHashEntry.setHashCode(EXAMPLE_HASHCODE.toString());
    realFileHashEntry.setContents(FILE_CONTENTS.getBytes(StandardCharsets.UTF_8));
    realFileHashEntry.setMaterializeDuringPreloading(materializeDuringPreloading);
    BuildJobStateFileHashes fileHashes = new BuildJobStateFileHashes();
    fileHashes.addToEntries(realFileHashEntry);

    InlineContentsProvider inlineProvider = new InlineContentsProvider();

    ProjectFileHashCache mockFileHashCache = EasyMock.createNiceMock(ProjectFileHashCache.class);
    expect(mockFileHashCache.getFilesystem()).andReturn(projectFilesystem).atLeastOnce();
    expect(mockFileHashCache.get(relativeRealFile))
        .andReturn(setCorrectHash ? EXAMPLE_HASHCODE : EXAMPLE_HASHCODE_TWO)
        .atLeastOnce();
    replay(mockFileHashCache);
    MaterializerProjectFileHashCache fileMaterializer =
        new MaterializerProjectFileHashCache(mockFileHashCache, fileHashes, inlineProvider);
    materializeFunction.execute(fileMaterializer, relativeRealFile);

    return realFileAbsPath;
  }

  @Test
  public void testMaterializeRealFileSetsContents() throws InterruptedException, IOException {
    // Scenario:
    //  path: /project/linktoexternaldir/externalfile
    //  contents: "filecontents"
    // => materialize creates file with correct contents
    Path realFile = testEntryForRealFile(false, GET, true);

    assertTrue(realFile.toFile().exists());
    String actualFileContents = new String(Files.readAllBytes(realFile));
    assertThat(actualFileContents, Matchers.equalTo(FILE_CONTENTS));
  }

  @Test
  public void testMaterializeRealFileDuringPreloadingSetsContents()
      throws InterruptedException, IOException {
    // Scenario:
    //  path: /project/linktoexternaldir/externalfile
    //  contents: "filecontents"
    // => preloading for entry with materializeDuringPreloading set to true
    // creates file with correct contents
    Path realFile = testEntryForRealFile(true, PRELOAD, true);

    assertTrue(realFile.toFile().exists());
    String actualFileContents = new String(Files.readAllBytes(realFile));
    assertThat(actualFileContents, Matchers.equalTo(FILE_CONTENTS));
  }

  @Test
  public void testPreloadRealFileTouchesFile() throws InterruptedException, IOException {
    // Scenario:
    //  path: /project/linktoexternaldir/externalfile
    //  contents: "filecontents"
    // => preload touches file, but doesn't set contents
    Path realFile = testEntryForRealFile(false, PRELOAD, true);

    assertTrue(realFile.toFile().exists());
    assertThat(realFile.toFile().length(), Matchers.equalTo(0L));
  }

  @Test
  public void testMaterializeFileWithDifferentHashCodeThrowsException()
      throws InterruptedException, IOException {
    testEntryForRealFile(false, THROWING_GET, false);
  }

  private void testSymlinkToFileWithinExternalDirectory(
      MaterializeFunction materializeFunction, boolean setCorrectHashCode)
      throws InterruptedException, IOException {
    // Scenario:
    //  path: /project/linktoexternaldir/externalfile
    //  symlink root: /project/linktoexternaldir -> /externalDir
    // => check that /project/linktoexternaldir/externalfile -> /externalDir/externalfile

    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());
    File externalFile = externalDir.newFile("externalfile");
    Path symlinkRoot = projectFilesystem.resolve("linktoexternaldir");
    Path relativeSymlinkRoot = Paths.get("linktoexternaldir");
    Path symlink = symlinkRoot.resolve("externalfile"); // /project/linktoexternaldir/externalfile
    Path relativeSymlink = projectFilesystem.getPathRelativeToProjectRoot(symlink).get();

    BuildJobStateFileHashEntry symlinkFileHashEntry = new BuildJobStateFileHashEntry();
    symlinkFileHashEntry.setRootSymLink(unixPath(relativeSymlinkRoot));
    symlinkFileHashEntry.setRootSymLinkTarget(unixPath(externalDir.getRoot().toPath()));
    symlinkFileHashEntry.setPath(unixPath(relativeSymlink));
    symlinkFileHashEntry.setHashCode(EXAMPLE_HASHCODE.toString());
    BuildJobStateFileHashes fileHashes = new BuildJobStateFileHashes();
    fileHashes.addToEntries(symlinkFileHashEntry);

    FileContentsProvider mockFileProvider = EasyMock.createMock(FileContentsProvider.class);
    ProjectFileHashCache mockFileHashCache = EasyMock.createNiceMock(ProjectFileHashCache.class);
    expect(mockFileHashCache.getFilesystem()).andReturn(projectFilesystem).atLeastOnce();
    expect(mockFileHashCache.get(relativeSymlink))
        .andReturn(setCorrectHashCode ? EXAMPLE_HASHCODE : EXAMPLE_HASHCODE_TWO)
        .atLeastOnce();
    replay(mockFileHashCache);

    MaterializerProjectFileHashCache fileMaterializer =
        new MaterializerProjectFileHashCache(mockFileHashCache, fileHashes, mockFileProvider);

    assertFalse(symlink.toFile().exists());

    materializeFunction.execute(fileMaterializer, relativeSymlink);

    if (setCorrectHashCode) {
      // If we set the wrong hash, we may or may not have materialized everything.
      assertTrue(symlink.toFile().exists());
      assertThat(symlink.toRealPath(), Matchers.equalTo(externalFile.toPath().toRealPath()));
    }
    verify(mockFileHashCache);
  }

  @Test
  public void testPreloadSymlinkToFileWithinExternalDirectory()
      throws InterruptedException, IOException {
    testSymlinkToFileWithinExternalDirectory(PRELOAD, true);
  }

  @Test
  public void testMaterializeSymlinkToFileWithinExternalDirectory()
      throws InterruptedException, IOException {
    testSymlinkToFileWithinExternalDirectory(GET, true);
  }

  @Test
  public void testPreloadThenMaterializeSymlinkToFileWithinExternalDirectory()
      throws InterruptedException, IOException {
    testSymlinkToFileWithinExternalDirectory(PRELOAD_THEN_GET, true);
  }

  @Test
  public void testMaterializeSymlinkWithDifferentHashCodeThrowsException()
      throws InterruptedException, IOException {
    testSymlinkToFileWithinExternalDirectory(THROWING_GET, false);
  }

  private static PathWithUnixSeparators unixPath(Path path) {
    return new PathWithUnixSeparators().setPath(MorePaths.pathWithUnixSeparators(path));
  }
}
