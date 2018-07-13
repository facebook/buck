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

package com.facebook.buck.features.project.intellij;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.features.project.intellij.lang.android.AndroidResourceFolder;
import com.facebook.buck.features.project.intellij.model.folders.ExcludeFolder;
import com.facebook.buck.features.project.intellij.model.folders.IjFolder;
import com.facebook.buck.features.project.intellij.model.folders.JavaResourceFolder;
import com.facebook.buck.features.project.intellij.model.folders.SourceFolder;
import com.facebook.buck.features.project.intellij.model.folders.TestFolder;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.DefaultJavaPackageFinder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

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
    return new TestFolder(Paths.get(path), true);
  }

  private static IjFolder buildNonCoalescingFolder(String path) {
    return new AndroidResourceFolder(Paths.get(path));
  }

  private static IjFolder buildJavaResourceFolder(String path, String resourcesRoot) {
    return new JavaResourceFolder(
        Paths.get(path), Paths.get(resourcesRoot), ImmutableSortedSet.of());
  }

  private static JavaPackageFinder fakePackageFinder() {
    return fakePackageFinder(ImmutableMap.of());
  }

  private static JavaPackageFinder fakePackageFinder(ImmutableMap<Path, Path> packageMap) {
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
        simplifier
            .simplify(0, ImmutableSet.of(left, right), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.contains(buildSourceFolder("src")));
  }

  @Test
  public void testSameTypeAndPackageAreMergedWithParent() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder left = buildSourceFolder("src/left");
    IjFolder right = buildSourceFolder("src/right");
    IjFolder parent = buildSourceFolder("src");

    assertThat(
        simplifier
            .simplify(0, ImmutableSet.of(left, right, parent), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.contains(parent));
  }

  @Test
  public void testMergedResourceFoldersShareSameResourcesRoot() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder left = buildJavaResourceFolder("res/left", "res");
    IjFolder right = buildJavaResourceFolder("res/right", "res");
    assertThat(
        simplifier
            .simplify(0, ImmutableSet.of(left, right), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.contains(buildJavaResourceFolder("res", "res")));
  }

  @Test
  public void testSinglePathElement() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder src = buildSourceFolder("src");

    assertThat(
        simplifier.simplify(0, ImmutableSet.of(src), Paths.get(""), ImmutableSet.of()).values(),
        Matchers.contains(src));
  }

  @Test
  public void testSinglePathElementMergesIntoParent() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder parent = buildSourceFolder("src");
    IjFolder child = buildSourceFolder("src/a");

    assertThat(
        simplifier
            .simplify(0, ImmutableSet.of(parent, child), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.contains(parent));
  }

  @Test
  public void testSimplificationLimit0() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder folder = buildSourceFolder("a/b/c/d/e/f/g");

    assertThat(
        simplifier.simplify(0, ImmutableSet.of(folder), Paths.get(""), ImmutableSet.of()).values(),
        Matchers.contains(buildSourceFolder("a")));
  }

  @Test
  public void testSimplificationLimit4() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder folder = buildSourceFolder("a/b/c/d/e/f/g");

    assertThat(
        simplifier.simplify(4, ImmutableSet.of(folder), Paths.get(""), ImmutableSet.of()).values(),
        Matchers.contains(buildSourceFolder("a/b/c/d")));
  }

  @Test
  public void testSimplificationLimit10() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder folder = buildSourceFolder("a/b/c/d/e/f/g");

    assertThat(
        simplifier.simplify(10, ImmutableSet.of(folder), Paths.get(""), ImmutableSet.of()).values(),
        Matchers.contains(buildSourceFolder("a/b/c/d/e/f/g")));
  }

  @Test
  public void testDifferentTypeAreNotMerged() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder leftSource = buildSourceFolder("src/left");
    IjFolder rightTest = buildTestFolder("src/right");

    assertThat(
        simplifier
            .simplify(0, ImmutableSet.of(leftSource, rightTest), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.containsInAnyOrder(buildSourceFolder("src"), rightTest));
  }

  @Test
  public void testDifferentTypeAreNotMergedWithParent() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder parent = buildSourceFolder("src");
    IjFolder leftSource = buildNoPrefixSourceFolder("src/left");
    IjFolder rightTest = buildTestFolder("src/right");

    assertThat(
        simplifier
            .simplify(
                0, ImmutableSet.of(parent, leftSource, rightTest), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.containsInAnyOrder(parent, leftSource, rightTest));
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

    ImmutableCollection<IjFolder> mergedFolders =
        simplifier
            .simplify(
                0,
                ImmutableSet.of(aaaSource, aaaaSource, aabSource, abSource, acTest, adaTest),
                Paths.get(""),
                ImmutableSet.of())
            .values();

    IjFolder aSource = buildSourceFolder("a");
    IjFolder adTest = buildTestFolder("a/d");
    assertThat(mergedFolders, Matchers.containsInAnyOrder(aSource, acTest, adTest));
  }

  @Test
  public void testDifferentResourcesRootsAreNotMerged() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder left = buildJavaResourceFolder("res/test/left", "res/test");
    IjFolder right = buildJavaResourceFolder("res/test/right", "res");
    assertThat(
        simplifier
            .simplify(0, ImmutableSet.of(left, right), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.containsInAnyOrder(left, right));
  }

  @Test
  public void testMergingIntoBiggerNumberOfSourceFolders() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder aaSource = buildSourceFolder("a/a");
    IjFolder abSource = buildSourceFolder("a/b");
    IjFolder acTest = buildTestFolder("a/c");

    ImmutableCollection<IjFolder> mergedFolders =
        simplifier
            .simplify(
                0, ImmutableSet.of(aaSource, abSource, acTest), Paths.get(""), ImmutableSet.of())
            .values();

    IjFolder aSource = buildSourceFolder("a");
    assertThat(mergedFolders, Matchers.containsInAnyOrder(aSource, acTest));
  }

  @Test
  public void testMergingIntoBiggerNumberOfTestFolders() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder aaSource = buildSourceFolder("a/a");
    IjFolder abSource = buildSourceFolder("a/b");
    IjFolder acTest = buildTestFolder("a/c");
    IjFolder adTest = buildTestFolder("a/d");
    IjFolder aeTest = buildTestFolder("a/e");

    ImmutableCollection<IjFolder> mergedFolders =
        simplifier
            .simplify(
                0,
                ImmutableSet.of(aaSource, abSource, acTest, adTest, aeTest),
                Paths.get(""),
                ImmutableSet.of())
            .values();

    IjFolder aTest = buildTestFolder("a");
    assertThat(mergedFolders, Matchers.containsInAnyOrder(aaSource, abSource, aTest));
  }

  @Test
  public void testDifferentTypesAreNotMergedIntoParent() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder aSource = buildSourceFolder("a");
    IjFolder aaaSource = buildSourceFolder("a/a/a");
    IjFolder aaaaSource = buildSourceFolder("a/a/a/a");
    IjFolder aabSource = buildSourceFolder("a/a/b");
    IjFolder abSource = buildSourceFolder("a/b");
    IjFolder acTest = buildTestFolder("a/c");
    IjFolder adaTest = buildTestFolder("a/d/a");

    ImmutableCollection<IjFolder> mergedFolders =
        simplifier
            .simplify(
                0,
                ImmutableSet.of(
                    aSource, aaaSource, aaaaSource, aabSource, abSource, acTest, adaTest),
                Paths.get(""),
                ImmutableSet.of())
            .values();

    IjFolder adTest = buildTestFolder("a/d");
    assertThat(mergedFolders, Matchers.containsInAnyOrder(aSource, acTest, adTest));
  }

  @Test
  public void testDifferentPackageHierarchiesAreNotMerged() {
    IjSourceRootSimplifier simplifier =
        new IjSourceRootSimplifier(
            fakePackageFinder(
                ImmutableMap.of(
                    Paths.get("src/left"), Paths.get("onething"),
                    Paths.get("src/right"), Paths.get("another"))));
    IjFolder leftSource = buildSourceFolder("src/left");
    IjFolder rightSource = buildTestFolder("src/right");

    assertThat(
        simplifier
            .simplify(0, ImmutableSet.of(leftSource, rightSource), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.containsInAnyOrder(leftSource, rightSource));
  }

  @Test
  public void testDifferentPackageHierarchiesAreNotMergedIntoParent() {
    IjSourceRootSimplifier simplifier =
        new IjSourceRootSimplifier(
            fakePackageFinder(
                ImmutableMap.of(
                    Paths.get("src"), Paths.get("onething"),
                    Paths.get("src/left"), Paths.get("onething/left"),
                    Paths.get("src/right"), Paths.get("another"))));
    IjFolder parentSource = buildSourceFolder("src");
    IjFolder leftSource = buildSourceFolder("src/left");
    IjFolder rightSource = buildTestFolder("src/right");

    assertThat(
        simplifier
            .simplify(
                0,
                ImmutableSet.of(parentSource, leftSource, rightSource),
                Paths.get(""),
                ImmutableSet.of())
            .values(),
        Matchers.containsInAnyOrder(parentSource, rightSource));
  }

  @Test
  public void testDifferentResourcesRootAreNotMergedIntoParent() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder parent = buildJavaResourceFolder("res/test", "res/test");
    IjFolder left = buildJavaResourceFolder("res/test/left", "res/test");
    IjFolder right = buildJavaResourceFolder("res/test/right", "res");

    assertThat(
        simplifier
            .simplify(0, ImmutableSet.of(parent, left, right), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.containsInAnyOrder(parent, right));
  }

  @Test
  public void testShortPackagesAreMerged() {
    IjSourceRootSimplifier simplifier =
        new IjSourceRootSimplifier(
            fakePackageFinder(
                ImmutableMap.of(
                    Paths.get("r/x/a/a"), Paths.get("a/a"),
                    Paths.get("r/x/a/b"), Paths.get("a/b"))));
    IjFolder aSource = buildSourceFolder("r/x/a/a");
    IjFolder bSource = buildSourceFolder("r/x/a/b");

    assertThat(
        simplifier
            .simplify(0, ImmutableSet.of(aSource, bSource), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.contains(buildSourceFolder("r/x")));
  }

  @Test
  public void testExcludeFoldersAreNotMerged() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder leftSource = buildSourceFolder("src/left");
    IjFolder aaExclude = buildExcludeFolder("src/a/a");
    IjFolder abExclude = buildExcludeFolder("src/a/b");

    assertThat(
        simplifier
            .simplify(
                0,
                ImmutableSet.of(leftSource, abExclude, aaExclude),
                Paths.get(""),
                ImmutableSet.of())
            .values(),
        Matchers.containsInAnyOrder(buildSourceFolder("src"), aaExclude, abExclude));
  }

  @Test
  public void testExcludeFoldersAreMergedIntoParent() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder leftSource = buildSourceFolder("src/left");
    IjFolder aExclude = buildExcludeFolder("src/a");
    IjFolder aaExclude = buildExcludeFolder("src/a/a");

    assertThat(
        simplifier
            .simplify(
                0,
                ImmutableSet.of(leftSource, aExclude, aaExclude),
                Paths.get(""),
                ImmutableSet.of())
            .values(),
        Matchers.containsInAnyOrder(buildSourceFolder("src"), aExclude));
  }

  @Test
  public void testExcludeFoldersAreNotMergedIntoParentWhenNonExcludedFoldersExist() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder leftSource = buildSourceFolder("src/left");
    IjFolder aaExclude = buildExcludeFolder("src/a/a");
    IjFolder abExclude = buildExcludeFolder("src/a/b");
    IjFolder acNonCoalescing = buildNonCoalescingFolder("src/a/c");

    assertThat(
        simplifier
            .simplify(
                0,
                ImmutableSet.of(leftSource, abExclude, aaExclude, acNonCoalescing),
                Paths.get(""),
                ImmutableSet.of())
            .values(),
        Matchers.containsInAnyOrder(
            buildSourceFolder("src"), abExclude, aaExclude, acNonCoalescing));
  }

  @Test
  public void testPrefixlessSourcesAreMergedToHighestRoot() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder aFolder = buildNoPrefixSourceFolder("src/a/b");
    IjFolder aaFolder = buildNoPrefixSourceFolder("src/a/a");
    IjFolder bFolder = buildNoPrefixSourceFolder("src/b");

    assertThat(
        simplifier
            .simplify(
                0, ImmutableSet.of(aFolder, aaFolder, bFolder), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.contains(buildNoPrefixSourceFolder("")));
  }

  @Test
  public void textPrefixAndPrefixlessSourcesDontMerge() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder aFolder = buildNoPrefixSourceFolder("src/a/b");
    IjFolder aaFolder = buildSourceFolder("src/a/a");
    IjFolder bFolder = buildNoPrefixSourceFolder("src/b");

    assertThat(
        simplifier
            .simplify(
                0, ImmutableSet.of(aFolder, aaFolder, bFolder), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.containsInAnyOrder(buildSourceFolder("src"), aFolder, bFolder));
  }

  @Test
  public void testNonCoalescingChildrenDontMerge() {
    IjSourceRootSimplifier simplifier = new IjSourceRootSimplifier(fakePackageFinder());
    IjFolder abFolder = buildSourceFolder("src/a/b");
    IjFolder abrFolder = buildNonCoalescingFolder("src/a/b/r");
    IjFolder acFolder = buildSourceFolder("src/a/c");

    assertThat(
        simplifier
            .simplify(
                0, ImmutableSet.of(abFolder, abrFolder, acFolder), Paths.get(""), ImmutableSet.of())
            .values(),
        Matchers.containsInAnyOrder(abrFolder, buildSourceFolder("src")));
  }
}
