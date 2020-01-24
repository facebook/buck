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

package com.facebook.buck.core.model.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
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
        BuildTargetFactory.newInstance("//my/folder:foo"), ForwardRelativePath.of("my/folder/foo__")
      },
      new Object[] {
        BuildTargetFactory.newInstance("//my/folder:foo#flavour"),
        ForwardRelativePath.of("my/folder/foo#flavour")
      }
    };
  }

  @Test
  @Parameters(method = "getTargetsForTest")
  public void genPathFormat(BuildTarget target, ForwardRelativePath path) {
    assertTrue(BuildPaths.getGenDir(filesystem, target).startsWith("buck-out/gen"));
    assertTrue(
        BuildPaths.getGenDir(filesystem, target).endsWith(path.toPath(filesystem.getFileSystem())));
  }

  @Test
  @Parameters(method = "getTargetsForTest")
  public void annotationPathFormat(BuildTarget target, ForwardRelativePath path) {
    assertTrue(BuildPaths.getAnnotationDir(filesystem, target).startsWith("buck-out/annotation"));
    assertTrue(
        BuildPaths.getAnnotationDir(filesystem, target)
            .endsWith(path.toPath(filesystem.getFileSystem())));
  }

  @Test
  @Parameters(method = "getTargetsForTest")
  public void scratchPathFormat(BuildTarget target, ForwardRelativePath path) {
    assertTrue(BuildPaths.getScratchDir(filesystem, target).startsWith("buck-out/bin"));
    assertTrue(
        BuildPaths.getScratchDir(filesystem, target)
            .endsWith(path.toPath(filesystem.getFileSystem())));
  }

  @Test
  @Parameters(method = "getTargetsForTest")
  public void basePathFormat(BuildTarget target, ForwardRelativePath path) {
    assertTrue(BuildPaths.getBaseDir(filesystem, target).endsWith(path));
  }

  @Test
  @Parameters(method = "getTargetsForTest")
  public void basePathFormatWithTargetConfigHash(BuildTarget target, ForwardRelativePath path) {
    ProjectFilesystem filesystem =
        FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(true);
    String hash = TargetConfigurationHasher.hash(target.getTargetConfiguration());
    assertEquals(
        BuildPaths.getBaseDir(filesystem, target), ForwardRelativePath.of(hash).resolve(path));
  }

  @Test
  public void removeTargetHashFromRelativePath() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my/folder:foo");

    ProjectFilesystem filesystemWithTargetConfigHashInBuckPaths =
        FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(true);
    ProjectFilesystem filesystemWithoutTargetConfigHashInBuckPaths =
        FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(false);
    Path hashedGenPath =
        BuildPaths.getGenDir(filesystemWithTargetConfigHashInBuckPaths, buildTarget)
            .resolve("a.out");
    Path legacyGenPath =
        BuildPaths.getGenDir(filesystemWithoutTargetConfigHashInBuckPaths, buildTarget)
            .resolve("a.out");

    Path hash = Paths.get(TargetConfigurationHasher.hash(buildTarget.getTargetConfiguration()));

    assertTrue(Iterables.contains(hashedGenPath, hash));
    assertFalse(Iterables.contains(legacyGenPath, hash));
    assertEquals(legacyGenPath, BuildPaths.removeHashFrom(hashedGenPath, buildTarget).get());
    assertEquals(Optional.empty(), BuildPaths.removeHashFrom(legacyGenPath, buildTarget));
  }

  @Test
  public void removeTargetHashFromAbsolutePath() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//my/folder:foo");

    ProjectFilesystem filesystemWithTargetConfigHashInBuckPaths =
        FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(true);
    ProjectFilesystem filesystemWithoutTargetConfigHashInBuckPaths =
        FakeProjectFilesystem.createFilesystemWithTargetConfigHashInBuckPaths(false);
    Path hashedGenPath =
        BuildPaths.getGenDir(filesystemWithTargetConfigHashInBuckPaths, buildTarget)
            .resolve("a.out")
            .toAbsolutePath();
    Path legacyGenPath =
        BuildPaths.getGenDir(filesystemWithoutTargetConfigHashInBuckPaths, buildTarget)
            .resolve("a.out")
            .toAbsolutePath();

    Path hash = Paths.get(TargetConfigurationHasher.hash(buildTarget.getTargetConfiguration()));

    assertTrue(Iterables.contains(hashedGenPath, hash));
    assertFalse(Iterables.contains(legacyGenPath, hash));
    assertEquals(legacyGenPath, BuildPaths.removeHashFrom(hashedGenPath, buildTarget).get());
    assertEquals(Optional.empty(), BuildPaths.removeHashFrom(legacyGenPath, buildTarget));
  }
}
