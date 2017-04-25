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

package com.facebook.buck.zip;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class SrcZipAwareFileBundlerTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  ProjectFilesystem filesystem;
  Path src;
  Path dest;
  Path subDirectoryFile1;
  Path subDirectoryFile2;
  Path subDirectoryFile3;
  Path basePath = Paths.get("base");

  public void bundleFiles(ImmutableSortedSet<SourcePath> immutableSortedSet) throws IOException {
    ImmutableList.Builder<Step> immutableStepList = ImmutableList.builder();

    new File(subDirectoryFile1.toString()).getParentFile().mkdirs();
    new File(subDirectoryFile2.toString()).getParentFile().mkdirs();
    new File(subDirectoryFile3.toString()).getParentFile().mkdirs();
    Files.createFile(subDirectoryFile1);
    Files.createFile(subDirectoryFile2);
    Files.createFile(subDirectoryFile3);

    SrcZipAwareFileBundler bundler = new SrcZipAwareFileBundler(basePath);
    bundler.copy(
        filesystem,
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()))),
        immutableStepList,
        dest,
        immutableSortedSet);

    ImmutableList<Step> builtStepList = immutableStepList.build();

    for (Step step : builtStepList) {
      try {
        step.execute(TestExecutionContext.newInstance());
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  public List<Path> getBundledFilesCollection() throws IOException {
    boolean hasDirectory = true;
    LinkedList<Path> bundledFilesCollection =
        new LinkedList<>(filesystem.getDirectoryContents(dest));

    while (hasDirectory) {
      hasDirectory = false;
      for (Path path : bundledFilesCollection) {
        if (Files.isDirectory(filesystem.getPathForRelativePath(path))) {
          hasDirectory = true;
          bundledFilesCollection.remove(path);
          bundledFilesCollection.addAll(filesystem.getDirectoryContents(path));
          break;
        }
      }
    }

    return bundledFilesCollection;
  }

  @Test
  public void shouldBundleFilesIfInputIsADirectory() throws InterruptedException, IOException {
    filesystem = new ProjectFilesystem(tmp.getRoot());
    src = Paths.get("src");
    dest = filesystem.getRootPath().resolve("dest");
    subDirectoryFile1 = filesystem.getRootPath().resolve("src/subDir/subDir2/file1");
    subDirectoryFile2 = filesystem.getRootPath().resolve("src/subDir/file2");
    subDirectoryFile3 = filesystem.getRootPath().resolve("src/file3");

    bundleFiles(ImmutableSortedSet.of(new PathSourcePath(filesystem, src)));

    List<Path> bundledFilesCollection = getBundledFilesCollection();

    assertSame(bundledFilesCollection.size(), 3);

    for (Path path : bundledFilesCollection) {
      Path relativePath = dest.relativize(filesystem.getPathForRelativePath(path));
      assertTrue(
          subDirectoryFile1.endsWith(relativePath)
              || subDirectoryFile2.endsWith(relativePath)
              || subDirectoryFile3.endsWith(relativePath));
    }
  }

  @Test
  public void shouldBundleFilesAndKeepHierarchy() throws InterruptedException, IOException {
    filesystem = new ProjectFilesystem(tmp.getRoot());
    src = Paths.get("src");
    dest = filesystem.getRootPath().resolve("dest");
    subDirectoryFile1 = filesystem.getRootPath().resolve("src/subDir/file1");
    subDirectoryFile2 = filesystem.getRootPath().resolve("src/file1");
    subDirectoryFile3 = filesystem.getRootPath().resolve("src/subDires/file1");

    bundleFiles(ImmutableSortedSet.of(new PathSourcePath(filesystem, src)));

    List<Path> bundledFilesCollection = getBundledFilesCollection();

    assertSame(bundledFilesCollection.size(), 3);

    for (Path path : bundledFilesCollection) {
      Path relativePath = dest.relativize(filesystem.getPathForRelativePath(path));
      assertTrue(
          subDirectoryFile1.endsWith(relativePath)
              || subDirectoryFile2.endsWith(relativePath)
              || subDirectoryFile3.endsWith(relativePath));
    }
  }

  @Test(expected = HumanReadableException.class)
  public void shouldThrowAnExceptionIfBundlerOverwritesFiles()
      throws InterruptedException, IOException {
    filesystem = new ProjectFilesystem(tmp.getRoot());

    dest = filesystem.getRootPath().resolve("dest");
    subDirectoryFile1 = filesystem.getRootPath().resolve("src1/subDir/file1");
    subDirectoryFile2 = filesystem.getRootPath().resolve("src2/subDir/file1");
    subDirectoryFile3 = filesystem.getRootPath().resolve("src1/subDir/file3");

    bundleFiles(
        ImmutableSortedSet.of(
            new PathSourcePath(filesystem, filesystem.getRootPath().relativize(subDirectoryFile1)),
            new PathSourcePath(filesystem, filesystem.getRootPath().relativize(subDirectoryFile2)),
            new PathSourcePath(
                filesystem, filesystem.getRootPath().relativize(subDirectoryFile3))));
  }

  @Test
  public void shouldBundleFilesAndKeepSrcFilesUnderBasePath()
      throws InterruptedException, IOException {
    filesystem = new ProjectFilesystem(tmp.getRoot());

    dest = filesystem.getRootPath().resolve("dest");
    subDirectoryFile1 = filesystem.getRootPath().resolve("src1/subDir/file1");
    subDirectoryFile2 = filesystem.getRootPath().resolve("src2/subDir/file2");
    subDirectoryFile3 = filesystem.getRootPath().resolve("src1/subDir/file3");

    bundleFiles(
        ImmutableSortedSet.of(
            new PathSourcePath(filesystem, filesystem.getRootPath().relativize(subDirectoryFile1)),
            new PathSourcePath(filesystem, filesystem.getRootPath().relativize(subDirectoryFile2)),
            new PathSourcePath(
                filesystem, filesystem.getRootPath().relativize(subDirectoryFile3))));

    List<Path> bundledFilesCollection = getBundledFilesCollection();

    assertSame(bundledFilesCollection.size(), 3);

    for (Path path : bundledFilesCollection) {
      Path relativePath = dest.relativize(filesystem.getPathForRelativePath(path));
      assertTrue(
          subDirectoryFile1.getFileName().equals(relativePath)
              || subDirectoryFile2.getFileName().equals(relativePath)
              || subDirectoryFile3.getFileName().equals(relativePath));
    }
  }
}
