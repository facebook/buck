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
package com.facebook.buck.core.model.impl;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class BuildPathsTest {

  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();

  @SuppressWarnings("unused")
  private Object getTargetsForTest() {
    return new Object[] {
      new Object[] {
        BuildTargetFactory.newInstance("//my/folder:foo"), Paths.get("my/folder/foo__")
      },
      new Object[] {
        BuildTargetFactory.newInstance("//my/folder:foo#flavour"),
        Paths.get("my/folder/foo#flavour")
      }
    };
  }

  @Test
  @Parameters(method = "getTargetsForTest")
  public void genPathFormat(BuildTarget target, Path path) {
    assertEquals(Paths.get("buck-out/gen").resolve(path), BuildPaths.getGenDir(filesystem, target));
  }

  @Test
  @Parameters(method = "getTargetsForTest")
  public void annotationPathFormat(BuildTarget target, Path path) {
    assertEquals(
        Paths.get("buck-out/annotation/").resolve(path),
        BuildPaths.getAnnotationDir(filesystem, target));
  }

  @Test
  @Parameters(method = "getTargetsForTest")
  public void scratchPathFormat(BuildTarget target, Path path) {
    assertEquals(
        Paths.get("buck-out/bin").resolve(path), BuildPaths.getScratchDir(filesystem, target));
  }

  @Test
  @Parameters(method = "getTargetsForTest")
  public void basePathFormat(BuildTarget target, Path path) {
    assertEquals(path, BuildPaths.getBaseDir(target));
  }
}
