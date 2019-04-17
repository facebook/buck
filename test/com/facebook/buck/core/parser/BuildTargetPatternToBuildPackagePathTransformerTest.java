/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.parser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.files.DirectoryList;
import com.facebook.buck.core.files.DirectoryListKey;
import com.facebook.buck.core.files.FileTree;
import com.facebook.buck.core.files.FileTreeKey;
import com.facebook.buck.core.files.ImmutableDirectoryList;
import com.facebook.buck.core.files.ImmutableDirectoryListKey;
import com.facebook.buck.core.files.ImmutableFileTree;
import com.facebook.buck.core.files.ImmutableFileTreeKey;
import com.facebook.buck.core.graph.transformation.FakeComputationEnvironment;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetPatternData.Kind;
import com.facebook.buck.core.parser.buildtargetparser.ImmutableBuildTargetPatternData;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class BuildTargetPatternToBuildPackagePathTransformerTest {

  @SuppressWarnings("unused")
  private Object getSinglePathParams() {
    return new Object[] {
      new Object[] {Kind.SINGLE, "target"},
      new Object[] {Kind.PACKAGE, ""}
    };
  };

  @Test
  @Parameters(method = "getSinglePathParams")
  @TestCaseName("canDiscoverSinglePath({0},{1})")
  public void canDiscoverSinglePath(Kind kind, String targetName) {
    BuildTargetPatternToBuildPackagePathTransformer transformer =
        BuildTargetPatternToBuildPackagePathTransformer.of("BUCK");

    DirectoryList dlist =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("dir1/dir2/file"), Paths.get("dir1/dir2/BUCK")),
            ImmutableSortedSet.of(Paths.get("dir1/dir2/dir3")),
            ImmutableSortedSet.of());
    DirectoryListKey dkey = ImmutableDirectoryListKey.of(Paths.get("dir1/dir2"));

    FakeComputationEnvironment env = new FakeComputationEnvironment(ImmutableMap.of(dkey, dlist));

    BuildTargetPatternToBuildPackagePathKey key =
        ImmutableBuildTargetPatternToBuildPackagePathKey.of(
            ImmutableBuildTargetPatternData.of("", kind, Paths.get("dir1/dir2"), targetName));

    BuildPackagePaths paths = transformer.transform(key, env);

    assertEquals(ImmutableSortedSet.of(Paths.get("dir1/dir2")), paths.getPackageRoots());
  }

  @Test
  public void canDiscoverRecursivePaths() {
    BuildTargetPatternToBuildPackagePathTransformer transformer =
        BuildTargetPatternToBuildPackagePathTransformer.of("BUCK");

    DirectoryList dlist1 =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("dir1/file"), Paths.get("dir1/BUCK")),
            ImmutableSortedSet.of(Paths.get("dir1/dir2")),
            ImmutableSortedSet.of());

    DirectoryList dlist2 =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("dir1/file")),
            ImmutableSortedSet.of(Paths.get("dir1/dir2/dir3")),
            ImmutableSortedSet.of());

    DirectoryList dlist3 =
        ImmutableDirectoryList.of(
            ImmutableSortedSet.of(Paths.get("dir1/dir2/dir3/BUCK")),
            ImmutableSortedSet.of(),
            ImmutableSortedSet.of());

    FileTree ftree3 = ImmutableFileTree.of(Paths.get("dir1/dir2/dir3"), dlist3, ImmutableMap.of());
    FileTree ftree2 =
        ImmutableFileTree.of(
            Paths.get("dir1/dir2"), dlist2, ImmutableMap.of(Paths.get("dir1/dir2/dir3"), ftree3));
    FileTree ftree1 =
        ImmutableFileTree.of(
            Paths.get("dir1"), dlist1, ImmutableMap.of(Paths.get("dir1/dir2"), ftree2));

    FileTreeKey fkey1 = ImmutableFileTreeKey.of(Paths.get("dir1"));

    FakeComputationEnvironment env = new FakeComputationEnvironment(ImmutableMap.of(fkey1, ftree1));

    BuildTargetPatternToBuildPackagePathKey key =
        ImmutableBuildTargetPatternToBuildPackagePathKey.of(
            ImmutableBuildTargetPatternData.of("", Kind.RECURSIVE, Paths.get("dir1"), ""));

    BuildPackagePaths paths = transformer.transform(key, env);

    assertEquals(
        ImmutableSortedSet.of(Paths.get("dir1"), Paths.get("dir1/dir2/dir3")),
        paths.getPackageRoots());
  }
}
