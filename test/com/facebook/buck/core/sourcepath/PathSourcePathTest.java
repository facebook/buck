/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.sourcepath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PathSourcePathTest {

  @Test
  public void shouldResolveFilesUsingTheBuildContextsFileSystem() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    PathSourcePath path = FakeSourcePath.of(projectFilesystem, "cheese");

    SourcePathResolverAdapter resolver = new TestActionGraphBuilder().getSourcePathResolver();
    Path resolved = resolver.getRelativePath(path);

    assertEquals(Paths.get("cheese"), resolved);
  }

  @Test
  public void shouldReturnTheOriginalPathAsTheReference() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Path expected = Paths.get("cheese");
    PathSourcePath path = FakeSourcePath.of(projectFilesystem, expected.toString());

    assertEquals(expected, path.getRelativePath());
  }

  @Test
  public void testComparisonAndHashcode() {
    Path root = Paths.get("root").toAbsolutePath();
    ProjectFilesystem projectFilesystem =
        new FakeProjectFilesystem(CanonicalCellName.rootCell(), root);
    Path relativePath1 = Paths.get("some/relative/path1");
    Path relativePath2 = Paths.get("some/relative/path2");
    PathSourcePath clonedPathA1 = PathSourcePath.of(projectFilesystem, relativePath1);
    PathSourcePath pathA1 = PathSourcePath.of(projectFilesystem, relativePath1);
    PathSourcePath pathA2 = PathSourcePath.of(projectFilesystem, relativePath2);

    // check relative paths
    assertEquals(relativePath1, pathA1.getRelativePath());
    assertEquals(relativePath2, pathA2.getRelativePath());

    // different instances, but everything is the same
    assertEquals(pathA1.hashCode(), clonedPathA1.hashCode());
    assertEquals(pathA1, clonedPathA1);
    assertEquals(0, pathA1.compareTo(clonedPathA1));
    // even though the underlying relative paths are the same, their names are not
    assertNotEquals(pathA1.hashCode(), pathA2.hashCode());
    assertNotEquals(pathA1, pathA2);
    assertNotEquals(0, pathA1.compareTo(pathA2));
  }

  @Test
  public void fromSourcePath() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PathSourcePath pathSourcePath = FakeSourcePath.of(filesystem, "test");

    assertThat(PathSourcePath.from(pathSourcePath), Matchers.equalTo(Optional.of(pathSourcePath)));

    assertThat(
        PathSourcePath.from(
            DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:rule"))),
        Matchers.equalTo(Optional.empty()));

    assertThat(
        PathSourcePath.from(
            ArchiveMemberSourcePath.of(pathSourcePath, filesystem.getPath("something"))),
        Matchers.equalTo(Optional.of(pathSourcePath)));
    assertThat(
        PathSourcePath.from(
            ArchiveMemberSourcePath.of(
                DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:rule")),
                filesystem.getPath("something"))),
        Matchers.equalTo(Optional.empty()));
  }
}
