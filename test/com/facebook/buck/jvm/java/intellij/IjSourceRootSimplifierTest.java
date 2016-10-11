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

package com.facebook.buck.jvm.java.intellij;

import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class IjSourceRootSimplifierTest {

  private static IjFolder buildExcludeFolder(String path) {
    return new ExcludeFolder(Paths.get(path));
  }

  private static IjFolder buildSourceFolder(String path) {
    return new SourceFolder(Paths.get(path), true);
  }

  private static IjFolder buildNoPrefixSourceFolder(String path) {
    return new SourceFolder(Paths.get(path));
  }

  private static IjFolder buildTestFolder(String path) {
    return new TestFolder(Paths.get(path));
  }

  private static IjFolder buildNonCoalescingFolder(String path) {
    return new AndroidResourceFolder(Paths.get(path));
  }

  private static JavaPackageFinder fakePackageFinder() {
    return fakePackageFinder(ImmutableMap.of());
  }

  private static JavaPackageFinder fakePackageFinder(final ImmutableMap<Path, Path> packageMap) {
    return new JavaPackageFinder() {
      @Override
      public Path findJavaPackageFolder(Path pathRelativeToProjectRoot) {
        // The Path given here is a path to a file, not a folder.
        pathRelativeToProjectRoot =
            Preconditions.checkNotNull(pathRelativeToProjectRoot.getParent());
        if (packageMap.containsKey(pathRelativeToProjectRoot)) {
          return packageMap.get(pathRelativeToProjectRoot);
        }
        return pathRelativeToProjectRoot;
      }

      @Override
      public String findJavaPackage(Path pathRelativeToProjectRoot) {
        return DefaultJavaPackageFinder.findJavaPackageWithPackageFolder(
            findJavaPackageFolder(pathRelativeToProjectRoot));
      }

      @Override
      public String findJavaPackage(BuildTarget buildTarget) {
        return findJavaPackage(buildTarget.getBasePath().resolve("removed"));
      }
    };
  }

  @Test
  public void testSameTypeAndPackageAreMerged() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder left = buildSourceFolder("src/left");
    IjFolder right = buildSourceFolder("src/right");

    assertThat(
        simplifier.simplify(SimplificationLimit.of(0), ImmutableSet.of(left, right)),
        Matchers.contains(buildSourceFolder("src")));
  }

  @Test
  public void testSinglePathElement() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder src = buildSourceFolder("src");

    assertThat(
        simplifier.simplify(SimplificationLimit.of(0), ImmutableSet.of(src)),
        Matchers.contains(src));
  }

  @Test
  public void testSimplificationLimit() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder folder = buildSourceFolder("a/b/c/d/e/f/g");

    assertThat(
        simplifier.simplify(SimplificationLimit.of(2), ImmutableSet.of(folder)),
        Matchers.contains(buildSourceFolder("a/b")));

    assertThat(
        simplifier.simplify(SimplificationLimit.of(4), ImmutableSet.of(folder)),
        Matchers.contains(buildSourceFolder("a/b/c/d")));

    assertThat(
        simplifier.simplify(SimplificationLimit.of(10), ImmutableSet.of(folder)),
        Matchers.contains(buildSourceFolder("a/b/c/d/e/f/g")));
  }

  @Test
  public void testComplexPathElement() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder src = buildSourceFolder("src/a/b/c/d");

    assertThat(
        simplifier.simplify(SimplificationLimit.of(0), ImmutableSet.of(src)),
        Matchers.contains(buildSourceFolder("src")));
  }

  @Test
  public void testDifferentTypeAreNotMerged() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder leftSource = buildSourceFolder("src/left");
    IjFolder rightTest = buildTestFolder("src/right");

    assertThat(
        simplifier.simplify(SimplificationLimit.of(0), ImmutableSet.of(leftSource, rightTest)),
        Matchers.containsInAnyOrder(leftSource, rightTest));
  }

  @Test
  public void testDifferentTypeAreNotMergedWhileSameOnesAre() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder aaaSource = buildSourceFolder("a/a/a");
    IjFolder aaaaSource = buildSourceFolder("a/a/a/a");
    IjFolder aabSource = buildSourceFolder("a/a/b");
    IjFolder abSource = buildSourceFolder("a/b");
    IjFolder acTest = buildTestFolder("a/c");
    IjFolder adaTest = buildTestFolder("a/d/a");

    ImmutableSet<IjFolder> mergedFolders = simplifier.simplify(
        SimplificationLimit.of(0),
        ImmutableSet.of(aaaSource, aaaaSource, aabSource, abSource, acTest, adaTest));

    IjFolder aaSource = buildSourceFolder("a/a");
    IjFolder adTest = buildTestFolder("a/d");
    assertThat(
        mergedFolders,
        Matchers.containsInAnyOrder(aaSource, abSource, acTest, adTest));
  }

  @Test
  public void testDifferentPackageHierarchiesAreNotMerged() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(
        fakePackageFinder(ImmutableMap.of(
                Paths.get("src/left"), Paths.get("onething"),
                Paths.get("src/right"), Paths.get("another"))));
    IjFolder leftSource = buildSourceFolder("src/left");
    IjFolder rightSource = buildTestFolder("src/right");

    assertThat(
        simplifier.simplify(SimplificationLimit.of(0), ImmutableSet.of(leftSource, rightSource)),
        Matchers.containsInAnyOrder(leftSource, rightSource));
  }

  @Test
  public void testShortPackagesAreNotMerged() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(
        fakePackageFinder(ImmutableMap.of(
                Paths.get("r/x/a/a"), Paths.get("a/a"),
                Paths.get("r/x/a/b"), Paths.get("a/b"))));
    IjFolder aSource = buildSourceFolder("r/x/a/a");
    IjFolder bSource = buildSourceFolder("r/x/a/b");

    assertThat(
        simplifier.simplify(SimplificationLimit.of(0), ImmutableSet.of(aSource, bSource)),
        Matchers.contains(buildSourceFolder("r/x")));
  }

  @Test
  public void testExcludeFoldersAreIgnored() {
    // While flattening source folder hierarchies is fine within certain bounds given the
    // information available in the set of IjFolders and their package information, it is not
    // possible to do anything with exclude folders at this level of abstraction.
    // That's fine though as the IjTemplateDataPreparer generates excludes at the highest possible
    // location in the file tree, so they don't need to be merged.

    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder leftSource = buildSourceFolder("src/left");
    IjFolder aExclude = buildExcludeFolder("src/a");
    IjFolder aaExclude = buildExcludeFolder("src/a/a");

    assertThat(
        simplifier.simplify(
            SimplificationLimit.of(0),
            ImmutableSet.of(leftSource, aExclude, aaExclude)),
        Matchers.containsInAnyOrder(buildSourceFolder("src"), aExclude, aaExclude));
  }

  @Test
  public void textPrefixlessSourcesAreMergedToHighestRoot() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder aFolder = buildNoPrefixSourceFolder("src/a/b");
    IjFolder aaFolder = buildNoPrefixSourceFolder("src/a/a");
    IjFolder bFolder = buildNoPrefixSourceFolder("src/b");

    assertThat(
        simplifier.simplify(
            SimplificationLimit.of(0),
            ImmutableSet.of(aFolder, aaFolder, bFolder)),
        Matchers.contains(buildNoPrefixSourceFolder("src")));
  }

  @Test
  public void textPrefixAndPrefixlessSourcesDontMerge() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder aFolder = buildNoPrefixSourceFolder("src/a/b");
    IjFolder aaFolder = buildSourceFolder("src/a/a");
    IjFolder bFolder = buildNoPrefixSourceFolder("src/b");

    assertThat(
        simplifier.simplify(
            SimplificationLimit.of(0),
            ImmutableSet.of(aFolder, aaFolder, bFolder)),
        Matchers.containsInAnyOrder(aFolder, aaFolder, bFolder));
  }

  @Test
  public void testNonCoalesingChildrenDontMerge() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder abFolder = buildSourceFolder("src/a/b");
    IjFolder abrFolder = buildNonCoalescingFolder("src/a/b/r");
    IjFolder acFolder = buildSourceFolder("src/a/c");

    assertThat(
        simplifier.simplify(
            SimplificationLimit.of(0),
            ImmutableSet.of(abFolder, abrFolder, acFolder)),
        Matchers.containsInAnyOrder(abFolder, abrFolder, acFolder));
  }

  @Test
  public void testDifferentFolderTypesArentMerged() {
    // If a TestFolder and a SourceFolder are merged problems can occur with the
    // deps whereby the TestFolder gets promoted to a SourceFolder, but its deps
    // still remain as test deps and so are unresolvable, so we should ensure
    // that the two dep streams are never crossed.
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder sourceFolderOne = buildSourceFolder("src/a/source/b");
    IjFolder sourceFolderTwo = buildSourceFolder("src/a/source/c");
    IjFolder testFolder = buildTestFolder("src/a/test");

    assertThat(
        simplifier.simplify(
          SimplificationLimit.of(0),
          ImmutableSet.of(sourceFolderOne, sourceFolderTwo, testFolder)),
        Matchers.containsInAnyOrder(buildSourceFolder("src/a/source/"), testFolder));
  }
}
