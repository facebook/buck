/*
 * Copyright 2013-present Facebook, Inc.
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
package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Suppliers;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class PathSourcePathTest {

  @Test
  public void shouldResolveFilesUsingTheBuildContextsFileSystem() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    PathSourcePath path = FakeSourcePath.of(projectFilesystem, "cheese");

    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestBuildRuleResolver()));
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
  public void testExplicitlySpecifiedName() {
    Path root = Paths.get("/root/");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(root);
    Path relativePath1 = Paths.get("some/relative/path1");
    Path relativePath2 = Paths.get("some/relative/path2");
    String nameA = "nameA";
    String nameB = "nameB";
    PathSourcePath clonedPathA1 =
        PathSourcePath.of(projectFilesystem, nameA, Suppliers.ofInstance(relativePath1));
    PathSourcePath pathA1 =
        PathSourcePath.of(projectFilesystem, nameA, Suppliers.ofInstance(relativePath1));
    PathSourcePath pathA2 =
        PathSourcePath.of(projectFilesystem, nameA, Suppliers.ofInstance(relativePath2));
    PathSourcePath pathB1 =
        PathSourcePath.of(projectFilesystem, nameB, Suppliers.ofInstance(relativePath1));
    PathSourcePath pathB2 =
        PathSourcePath.of(projectFilesystem, nameB, Suppliers.ofInstance(relativePath2));

    // check relative paths
    assertEquals(relativePath1, pathA1.getRelativePath());
    assertEquals(relativePath2, pathA2.getRelativePath());
    assertEquals(relativePath1, pathB1.getRelativePath());
    assertEquals(relativePath2, pathB2.getRelativePath());

    // different instances, but everything is the same
    assertEquals(pathA1.hashCode(), clonedPathA1.hashCode());
    assertEquals(pathA1, clonedPathA1);
    assertEquals(0, pathA1.compareTo(clonedPathA1));
    // even though the underlying relative paths are different, their names are the same
    assertEquals(pathA1.hashCode(), pathA2.hashCode());
    assertEquals(pathA1, pathA2);
    assertEquals(0, pathA1.compareTo(pathA2));
    // even though the underlying relative paths are the same, their names are not
    assertNotEquals(pathA1.hashCode(), pathB1.hashCode());
    assertNotEquals(pathA1, pathB1);
    assertNotEquals(0, pathA1.compareTo(pathB1));
  }
}
