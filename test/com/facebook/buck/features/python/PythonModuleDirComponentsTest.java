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
package com.facebook.buck.features.python;

import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PythonModuleDirComponentsTest {

  @Test
  public void forEachInput() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePath dir = PathSourcePath.of(filesystem, filesystem.getPath("dir"));
    PythonComponents components = PythonModuleDirComponents.of(dir);
    List<SourcePath> inputs = new ArrayList<>();
    components.forEachInput(inputs::add);
    assertThat(inputs, Matchers.contains(dir));
  }

  @Test
  public void resolvePythonComponents() throws IOException {
    assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));
    FileSystem vfs = Jimfs.newFileSystem("testfs");
    Path root = vfs.getPath("/root");
    Files.createDirectories(root);
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(root);
    Path dir = filesystem.getPath("dir");
    filesystem.mkdirs(dir);
    filesystem.touch(dir.resolve("file1"));
    filesystem.touch(dir.resolve("file2"));
    filesystem.mkdirs(dir.resolve("subdir"));
    filesystem.touch(dir.resolve("subdir").resolve("file"));
    PythonComponents components = PythonModuleDirComponents.of(PathSourcePath.of(filesystem, dir));
    Map<Path, Path> resolved = new HashMap<>();
    components
        .resolvePythonComponents(new TestActionGraphBuilder().getSourcePathResolver())
        .forEachPythonComponent(resolved::put);
    assertThat(
        resolved,
        Matchers.equalTo(
            ImmutableMap.of(
                vfs.getPath("file1"), root.resolve(dir.resolve("file1")),
                vfs.getPath("file2"), root.resolve(dir.resolve("file2")),
                vfs.getPath("subdir").resolve("file"),
                    root.resolve(dir.resolve("subdir").resolve("file")))));
  }
}
