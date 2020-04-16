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

package com.facebook.buck.apple.xcode.xcodeproj;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.facebook.buck.apple.xcode.AbstractPBXObjectFactory;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class PBXProjectTest {
  @Test
  public void testProjectWithMainGroupContextSetCreatesRelativePaths() {
    SettableFakeClock clock = SettableFakeClock.DO_NOT_CARE;
    ProjectFilesystem fakeProjectFilesystem = new FakeProjectFilesystem(clock);
    PBXProject.MainGroupContext mainGroupContext =
        new PBXProject.MainGroupContext(
            fakeProjectFilesystem.getRootPath().resolve("/foo/bar/baz"),
            RelPath.of(Paths.get("baz/hux")));

    PBXProject project =
        new PBXProject(
            "TestProject",
            Optional.of(mainGroupContext),
            AbstractPBXObjectFactory.DefaultFactory());
    PBXGroup appGroup = project.getMainGroup().getOrCreateChildGroupByName("app");
    appGroup.getOrCreateDescendantGroupByPath(ImmutableList.of("src", "files"));

    assertEquals(project.getMainGroup().getPath(), RelPath.of(Paths.get("../..")).toString());
    assertEquals(project.getMainGroup().getSourceTree(), PBXReference.SourceTree.SOURCE_ROOT);

    List<PBXReference> children = project.getMainGroup().getChildren();
    assertFalse(children.isEmpty());

    while (!children.isEmpty()) {
      PBXGroup group = (PBXGroup) children.remove(0);
      assertEquals(group.getPath(), group.getName());
      assertEquals(group.getSourceTree(), PBXReference.SourceTree.GROUP);
      children.addAll(group.getChildren());
    }
  }

  @Test
  public void testProjectWithoutMainGroupSetHasNochildren() {
    PBXProject project =
        new PBXProject("TestProject", Optional.empty(), AbstractPBXObjectFactory.DefaultFactory());
    PBXGroup appGroup = project.getMainGroup().getOrCreateChildGroupByName("app");
    appGroup.getOrCreateDescendantGroupByPath(ImmutableList.of("src", "files"));

    assertNull(project.getMainGroup().getPath());
    assertEquals(project.getMainGroup().getSourceTree(), PBXReference.SourceTree.GROUP);

    List<PBXReference> children = project.getMainGroup().getChildren();
    assertFalse(children.isEmpty());

    while (!children.isEmpty()) {
      PBXGroup group = (PBXGroup) children.remove(0);
      assertNull(group.getPath());
      assertEquals(group.getSourceTree(), PBXReference.SourceTree.GROUP);
      children.addAll(group.getChildren());
    }
  }
}
