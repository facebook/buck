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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.features.project.intellij.model.folders.IJFolderFactory;
import com.facebook.buck.features.project.intellij.model.folders.IjFolder;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public abstract class IjFolderTest {

  IJFolderFactory folderFactory;

  public abstract void setupFolderFactory();

  @Test
  public void testMergeWithSelf() {
    Path srcPath = Paths.get("src");
    IjFolder folder =
        folderFactory.create(srcPath, false, ImmutableSortedSet.of(Paths.get("Source.java")));

    assertEquals(
        "Merging " + folder + " with itself didn't result in the same folder being returned.",
        folder,
        folder.merge(folder));
  }

  @Test
  public void testMergeSourceWithSamePath() {
    Path srcPath = Paths.get("src");
    testSamePathMerge(
        folderFactory.create(srcPath, false, ImmutableSortedSet.of(Paths.get("Source.java"))),
        folderFactory.create(srcPath, false, ImmutableSortedSet.of(Paths.get("Source2.java"))));
  }

  @Test
  public void testMergeParentWithChild() {
    Path parentPath = Paths.get("src");
    Path childPath = Paths.get("src/child");
    testMergeParentWithChild(
        folderFactory.create(parentPath, false, ImmutableSortedSet.of(Paths.get("Source.java"))),
        folderFactory.create(childPath, false, ImmutableSortedSet.of(Paths.get("Source2.java"))));
  }

  @Ignore
  private void testMergeParentWithChild(IjFolder parent, IjFolder child) {
    IjFolder mergedFolder = child.merge(parent);

    assertEquals(
        "Path of merged child and parent is not that of the parent",
        mergedFolder.getPath(),
        parent.getPath());

    ImmutableSortedSet<Path> expectedMergedInputs =
        ImmutableSortedSet.<Path>naturalOrder()
            .addAll(parent.getInputs())
            .addAll(child.getInputs())
            .build();

    assertEquals(
        "Combined parent and child input paths are not equial to the inputs from the two folders",
        mergedFolder.getInputs(),
        expectedMergedInputs);
  }

  @Ignore
  private void testSamePathMerge(IjFolder folder1, IjFolder folder2) {
    IjFolder mergedFolder = folder1.merge(folder2);

    assertEquals(
        "Merged folder isn't in the same folder as " + folder1,
        mergedFolder.getPath(),
        folder1.getPath());

    assertEquals(
        "Merged folder isn't in the same folder as " + folder2,
        mergedFolder.getPath(),
        folder2.getPath());

    ImmutableSortedSet<Path> expectedMergedInputs =
        ImmutableSortedSet.<Path>naturalOrder()
            .addAll(folder1.getInputs())
            .addAll(folder2.getInputs())
            .build();

    assertEquals(
        "Merged folder does not contain the inputs of the two separate folders",
        mergedFolder.getInputs(),
        expectedMergedInputs);
  }

  public void testMerge(IJFolderFactory otherFactory) {
    Path path = Paths.get("/src");

    IjFolder left = folderFactory.create(path, false, ImmutableSortedSet.of());
    IjFolder right = otherFactory.create(path, false, ImmutableSortedSet.of());

    if (!left.canMergeWith(right)) {
      throw new IllegalArgumentException("Can't merge " + left + " with " + right);
    }

    left.merge(right);
  }
}
