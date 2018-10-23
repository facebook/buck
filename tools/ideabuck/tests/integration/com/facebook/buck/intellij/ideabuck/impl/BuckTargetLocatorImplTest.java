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

package com.facebook.buck.intellij.ideabuck.impl;

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.facebook.buck.intellij.ideabuck.api.BuckCellManager.Cell;
import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.PlatformTestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.easymock.EasyMock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Unit test for {@link BuckTargetLocatorImpl}. */
public class BuckTargetLocatorImplTest extends PlatformTestCase {

  private Project project;
  private VirtualFileManager virtualFileManager;
  private PsiManager psiManager;
  private BuckCellManager buckCellManager;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    project = getProject();
    virtualFileManager = VirtualFileManager.getInstance();
    psiManager = PsiManager.getInstance(project);
    buckCellManager = EasyMock.createMock(BuckCellManager.class);
  }

  private Cell createCell(@Nullable String name) {
    return createCell(name, "BUCK");
  }

  private Cell createCell(@Nullable String name, String buildFileName) {
    VirtualFile root = getTempDir().createTempVDir();
    Path path = Paths.get(root.getCanonicalPath());
    return new Cell() {
      @Override
      public Optional<String> getName() {
        return Optional.ofNullable(name);
      }

      @Override
      public String getBuildfileName() {
        return buildFileName;
      }

      @Override
      public Optional<VirtualFile> getRootDirectory() {
        return Optional.of(root);
      }

      @Override
      public Path getRootPath() {
        return path;
      }
    };
  }

  @NotNull
  private <T> T unwrap(Optional<T> optional) {
    T t = optional.orElse(null);
    assertNotNull(t);
    return t;
  }

  private <T> void assertOptionalEquals(T expected, Optional<T> actual) {
    assertEquals(expected, actual.orElse(null));
  }

  // Test findPathForTarget and findVirtualFileForTarget together

  public void testFindTargetInDefaultCell() throws IOException {
    Cell cell = createCell(null, "BUILD");
    buckCellManager.getDefaultCell();
    EasyMock.expectLastCall().andReturn(Optional.of(cell)).anyTimes();
    EasyMock.replay(buckCellManager);

    String relativePath = "foo/bar/BUILD";
    Path expectedPath = cell.getRootPath().resolve(relativePath);
    expectedPath.toFile().getParentFile().mkdirs();
    Files.write(expectedPath, new byte[0]);
    VirtualFile expectedVirtualFile =
        unwrap(cell.getRootDirectory()).findFileByRelativePath(relativePath);

    BuckTargetLocatorImpl targetLocator =
        new BuckTargetLocatorImpl(virtualFileManager, psiManager, buckCellManager);

    BuckTarget target = unwrap(BuckTarget.parse("//foo/bar:baz"));
    assertOptionalEquals(expectedPath, targetLocator.findPathForTarget(target));
    assertOptionalEquals(expectedVirtualFile, targetLocator.findVirtualFileForTarget(target));
  }

  public void testFindTargetInNamedCell() throws IOException {
    Cell cell = createCell("foo", "FOOBUCK");
    buckCellManager.findCellByName("foo");
    EasyMock.expectLastCall().andReturn(Optional.of(cell)).anyTimes();
    EasyMock.replay(buckCellManager);

    String relativePath = "bar/baz/FOOBUCK";
    Path expectedPath = cell.getRootPath().resolve(relativePath);
    expectedPath.toFile().getParentFile().mkdirs();
    Files.write(expectedPath, new byte[0]);
    VirtualFile expectedVirtualFile =
        unwrap(cell.getRootDirectory()).findFileByRelativePath(relativePath);

    BuckTargetLocatorImpl targetLocator =
        new BuckTargetLocatorImpl(virtualFileManager, psiManager, buckCellManager);

    BuckTarget target = unwrap(BuckTarget.parse("foo//bar/baz:qux"));
    assertOptionalEquals(expectedPath, targetLocator.findPathForTarget(target));
    assertOptionalEquals(expectedVirtualFile, targetLocator.findVirtualFileForTarget(target));
  }

  // Test findPathForExtensionFile and findVirtualFileForExtensionFile together

  public void testFindExtensionFileInDefaultCell() throws IOException {
    Cell cell = createCell(null);
    buckCellManager.getDefaultCell();
    EasyMock.expectLastCall().andReturn(Optional.of(cell)).anyTimes();
    EasyMock.replay(buckCellManager);

    String relativePath = "foo/bar/baz/qux.bzl";
    Path expectedPath = cell.getRootPath().resolve(relativePath);
    expectedPath.toFile().getParentFile().mkdirs();
    Files.write(expectedPath, new byte[0]);
    VirtualFile expectedVirtualFile =
        unwrap(cell.getRootDirectory()).findFileByRelativePath(relativePath);

    BuckTargetLocatorImpl targetLocator =
        new BuckTargetLocatorImpl(virtualFileManager, psiManager, buckCellManager);

    BuckTarget target = unwrap(BuckTarget.parse("//foo/bar:baz/qux.bzl"));
    assertOptionalEquals(expectedPath, targetLocator.findPathForExtensionFile(target));
    assertOptionalEquals(
        expectedVirtualFile, targetLocator.findVirtualFileForExtensionFile(target));
  }

  public void testFindExtensionFileInNamedCell() throws IOException {
    Cell cell = createCell("foo");
    buckCellManager.findCellByName("foo");
    EasyMock.expectLastCall().andReturn(Optional.of(cell)).anyTimes();
    EasyMock.replay(buckCellManager);

    String relativePath = "bar/baz/qux/quux.bzl";
    Path expectedPath = cell.getRootPath().resolve(relativePath);
    expectedPath.toFile().getParentFile().mkdirs();
    Files.write(expectedPath, new byte[0]);
    VirtualFile expectedVirtualFile =
        unwrap(cell.getRootDirectory()).findFileByRelativePath(relativePath);

    BuckTargetLocatorImpl targetLocator =
        new BuckTargetLocatorImpl(virtualFileManager, psiManager, buckCellManager);

    BuckTarget target = unwrap(BuckTarget.parse("foo//bar/baz:qux/quux.bzl"));
    assertOptionalEquals(expectedPath, targetLocator.findPathForExtensionFile(target));
    assertOptionalEquals(
        expectedVirtualFile, targetLocator.findVirtualFileForExtensionFile(target));
  }

  // Test findPathForTargetPattern and findVirtualFileForTargetPattern together

  public void testFindPathForTargetPatternInDefaultCell() throws IOException {
    Cell cell = createCell(null, "BUILD");
    buckCellManager.getDefaultCell();
    EasyMock.expectLastCall().andReturn(Optional.of(cell)).anyTimes();
    EasyMock.replay(buckCellManager);

    String relativePath = "foo/bar/BUILD";
    Path expectedPathToFile = cell.getRootPath().resolve(relativePath);
    Path expectedPathToDir = expectedPathToFile.getParent();
    expectedPathToDir.toFile().mkdirs();
    Files.write(expectedPathToFile, new byte[0]);

    unwrap(cell.getRootDirectory()).refresh(false, true);
    VirtualFile expectedVirtualFileToFile =
        unwrap(cell.getRootDirectory()).findFileByRelativePath(relativePath);
    VirtualFile expectedVirtualFileToDir = expectedVirtualFileToFile.getParent();

    BuckTargetLocatorImpl targetLocator =
        new BuckTargetLocatorImpl(virtualFileManager, psiManager, buckCellManager);

    BuckTargetPattern explicitTargetPattern = unwrap(BuckTargetPattern.parse("//foo/bar:baz"));
    assertOptionalEquals(
        expectedPathToFile, targetLocator.findPathForTargetPattern(explicitTargetPattern));
    assertOptionalEquals(
        expectedVirtualFileToFile,
        targetLocator.findVirtualFileForTargetPattern(explicitTargetPattern));

    BuckTargetPattern implicitTargetPattern = unwrap(BuckTargetPattern.parse("//foo/bar"));
    assertOptionalEquals(
        expectedPathToFile, targetLocator.findPathForTargetPattern(implicitTargetPattern));
    assertOptionalEquals(
        expectedVirtualFileToFile,
        targetLocator.findVirtualFileForTargetPattern(implicitTargetPattern));

    BuckTargetPattern allTargetsInPackage = unwrap(BuckTargetPattern.parse("//foo/bar:"));
    assertOptionalEquals(
        expectedPathToFile, targetLocator.findPathForTargetPattern(allTargetsInPackage));
    assertOptionalEquals(
        expectedVirtualFileToFile,
        targetLocator.findVirtualFileForTargetPattern(allTargetsInPackage));

    BuckTargetPattern recursiveTarget = unwrap(BuckTargetPattern.parse("//foo/bar/..."));
    assertOptionalEquals(
        expectedPathToDir, targetLocator.findPathForTargetPattern(recursiveTarget));
    assertOptionalEquals(
        expectedVirtualFileToDir, targetLocator.findVirtualFileForTargetPattern(recursiveTarget));
  }

  public void testFindPathForTargetPatternInNamedCell() throws IOException {
    Cell cell = createCell("foo", "FOOBUCK");
    buckCellManager.findCellByName("foo");
    EasyMock.expectLastCall().andReturn(Optional.of(cell)).anyTimes();
    EasyMock.replay(buckCellManager);

    String relativePath = "bar/baz/FOOBUCK";
    Path expectedPathToFile = cell.getRootPath().resolve(relativePath);
    Path expectedPathToDir = expectedPathToFile.getParent();
    expectedPathToDir.toFile().mkdirs();
    Files.write(expectedPathToFile, new byte[0]);

    unwrap(cell.getRootDirectory()).refresh(false, true);
    VirtualFile expectedVirtualFileToFile =
        unwrap(cell.getRootDirectory()).findFileByRelativePath(relativePath);
    VirtualFile expectedVirtualFileToDir = expectedVirtualFileToFile.getParent();

    BuckTargetLocatorImpl targetLocator =
        new BuckTargetLocatorImpl(virtualFileManager, psiManager, buckCellManager);

    BuckTargetPattern explicitTargetPattern = unwrap(BuckTargetPattern.parse("foo//bar/baz:qux"));
    assertOptionalEquals(
        expectedPathToFile, targetLocator.findPathForTargetPattern(explicitTargetPattern));
    assertOptionalEquals(
        expectedVirtualFileToFile,
        targetLocator.findVirtualFileForTargetPattern(explicitTargetPattern));

    BuckTargetPattern implicitTargetPattern = unwrap(BuckTargetPattern.parse("foo//bar/baz"));
    assertOptionalEquals(
        expectedPathToFile, targetLocator.findPathForTargetPattern(implicitTargetPattern));
    assertOptionalEquals(
        expectedVirtualFileToFile,
        targetLocator.findVirtualFileForTargetPattern(implicitTargetPattern));

    BuckTargetPattern allTargetsInPackage = unwrap(BuckTargetPattern.parse("foo//bar/baz:"));
    assertOptionalEquals(
        expectedPathToFile, targetLocator.findPathForTargetPattern(allTargetsInPackage));
    assertOptionalEquals(
        expectedVirtualFileToFile,
        targetLocator.findVirtualFileForTargetPattern(allTargetsInPackage));

    BuckTargetPattern recursiveTarget = unwrap(BuckTargetPattern.parse("foo//bar/baz/..."));
    assertOptionalEquals(
        expectedPathToDir, targetLocator.findPathForTargetPattern(recursiveTarget));
    assertOptionalEquals(
        expectedVirtualFileToDir, targetLocator.findVirtualFileForTargetPattern(recursiveTarget));
  }
}
