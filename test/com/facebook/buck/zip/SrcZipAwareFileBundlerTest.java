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

import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;

public class SrcZipAwareFileBundlerTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();
  ProjectFilesystem filesystem;
  Path src;
  Path dest;
  Path subDirectoryFile1;
  Path subDirectoryFile2;
  Path subDirectoryFile3;

  public void bundleFiles(ImmutableSortedSet<SourcePath> immutableSortedSet) throws IOException {
    ImmutableList.Builder<Step> immutableStepList = ImmutableList.<Step>builder();
    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer()));

    new File(subDirectoryFile1.toString()).getParentFile().mkdirs();
    new File(subDirectoryFile2.toString()).getParentFile().mkdirs();
    new File(subDirectoryFile3.toString()).getParentFile().mkdirs();
    Files.createFile(subDirectoryFile1);
    Files.createFile(subDirectoryFile2);
    Files.createFile(subDirectoryFile3);

    List<String> srcPathStrings = new LinkedList<>();
    for (SourcePath sourcePath : immutableSortedSet) {
      srcPathStrings.add("/" + resolver.getAbsolutePath(sourcePath).toString());
    }

    DefaultJavaPackageFinder javaPackageFinder = DefaultJavaPackageFinder
        .createDefaultJavaPackageFinder(srcPathStrings);

    SrcZipAwareFileBundler bundler = new SrcZipAwareFileBundler(
        javaPackageFinder);
    bundler.copy(
        filesystem,
        resolver,
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

  @Test
  public void shouldBundleFilesIfInputIsADirectory() throws IOException {
    boolean hasDirectory = true;
    filesystem = new ProjectFilesystem(tmp.getRoot());
    src = filesystem.getRootPath().resolve("src");
    dest = filesystem.getRootPath().resolve("dest");
    subDirectoryFile1 = filesystem.getRootPath().resolve("src/subDir/subDir2/file1");
    subDirectoryFile2 = filesystem.getRootPath().resolve("src/subDir/file2");
    subDirectoryFile3 = filesystem.getRootPath().resolve("src/file3");

    bundleFiles(ImmutableSortedSet.<SourcePath>of(new PathSourcePath(filesystem, src)));

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

    assertSame(bundledFilesCollection.size(), 3);

    for (Path path : bundledFilesCollection) {
      Path relativePath = dest.relativize(filesystem.getPathForRelativePath(path));
      assertTrue(
          subDirectoryFile1.endsWith(relativePath) ||
              subDirectoryFile2.endsWith(relativePath) ||
              subDirectoryFile3.endsWith(relativePath));
    }
  }

  @Test
  public void shouldBundleFilesAndKeepHierarchyIfPathIsInBuckOut() throws IOException {
    boolean hasDirectory = true;
    filesystem = new ProjectFilesystem(tmp.getRoot());
    src = filesystem.getRootPath().resolve("src");
    dest = filesystem.getRootPath().resolve("dest");
    subDirectoryFile1 = filesystem.getRootPath().resolve("src/subDir/file1");
    subDirectoryFile2 = filesystem.getRootPath().resolve("src/file1");
    subDirectoryFile3 = filesystem.getRootPath().resolve("src/subDires/file1");

    bundleFiles(ImmutableSortedSet.<SourcePath>of(new PathSourcePath(filesystem, src)));

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

    assertSame(bundledFilesCollection.size(), 3);

    for (Path path : bundledFilesCollection) {
      Path relativePath = dest.relativize(filesystem.getPathForRelativePath(path));
      assertTrue(
          subDirectoryFile1.endsWith(relativePath) ||
          subDirectoryFile2.endsWith(relativePath) ||
          subDirectoryFile3.endsWith(relativePath));
    }
  }
}
