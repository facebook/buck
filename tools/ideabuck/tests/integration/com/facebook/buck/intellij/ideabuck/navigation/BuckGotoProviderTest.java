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
package com.facebook.buck.intellij.ideabuck.navigation;

import com.facebook.buck.intellij.ideabuck.config.BuckCell;
import com.facebook.buck.intellij.ideabuck.config.BuckCellSettingsProvider;
import com.facebook.buck.intellij.ideabuck.endtoend.BuckTestCase;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionCall;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckString;
import com.facebook.buck.intellij.ideabuck.util.BuckPsiUtils;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.TempFiles;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public class BuckGotoProviderTest extends BuckTestCase {

  TempFiles tempFiles;
  VirtualFile mainCellRoot;
  VirtualFile otherCellRoot;
  BuckGotoProvider buckGotoProvider;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    Project project = super.getProject();
    BuckCellSettingsProvider buckCellSettingsProvider =
        BuckCellSettingsProvider.getInstance(project);

    tempFiles = new TempFiles(new ArrayList<>());
    mainCellRoot = tempFiles.createTempVDir("main");
    otherCellRoot = tempFiles.createTempVDir("other");

    BuckCell mainCell = new BuckCell().withName("main").withRoot(mainCellRoot.getPath());
    BuckCell otherCell = new BuckCell().withName("other").withRoot(otherCellRoot.getPath());
    buckCellSettingsProvider.setCells(Arrays.asList(mainCell, otherCell));

    buckGotoProvider = new BuckGotoProvider();
  }

  @Override
  protected void tearDown() throws Exception {
    tempFiles.deleteAll();
    super.tearDown();
  }

  private VirtualFile makeDirectory(VirtualFile root, String relativePath) {
    Paths.get(root.getPath()).resolve(relativePath).toFile().mkdirs();
    root.refresh(false, true);
    return root.findFileByRelativePath(relativePath);
  }

  private VirtualFile makeFile(VirtualFile root, String relativePath, String... lines)
      throws IOException {
    File targetFile = Paths.get(root.getPath()).resolve(relativePath).toFile();
    targetFile.getParentFile().mkdirs();
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(targetFile))) {
      for (String line : lines) {
        writer.write(line);
        writer.newLine();
      }
    }
    root.refresh(false, true);
    return root.findFileByRelativePath(relativePath);
  }

  private PsiFile toPsiFile(VirtualFile virtualFile) {
    return getPsiManager().findFile(virtualFile);
  }

  private PsiElement findStringInFile(String s, VirtualFile virtualFile) {
    PsiFile psiFile = toPsiFile(virtualFile);
    for (PsiElement candidate : PsiTreeUtil.findChildrenOfType(psiFile, BuckString.class)) {
      String value = BuckPsiUtils.getStringValueFromBuckString(candidate);
      if (s.equals(value)) {
        return candidate;
      }
    }
    fail("Could not find string " + s + " in file " + virtualFile.getPath());
    return null;
  }

  @Test
  public void testResolveLoadTargetToPackageRelativeExtensionFile() throws IOException {
    VirtualFile sourceFile = makeFile(mainCellRoot, "foo/BUCK", "load(':bar/baz.bzl', 'x')");
    VirtualFile targetFile = makeFile(mainCellRoot, "foo/bar/baz.bzl", "x=1");
    PsiElement sourceElement = findStringInFile(":bar/baz.bzl", sourceFile);

    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertEquals(toPsiFile(targetFile), actualTarget);
  }

  @Test
  public void testResolveLoadTargetMissing() throws IOException {
    VirtualFile sourceFile = makeFile(mainCellRoot, "foo/BUCK", "load(':bar.bzl', 'x')");
    PsiElement sourceElement = findStringInFile(":bar.bzl", sourceFile);
    assertNotNull("Test precondition", sourceElement);

    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertNull(actualTarget);
  }

  @Test
  public void testResolveLoadTargetToAbsoluteExtensionFile() throws IOException {
    VirtualFile sourceFile = makeFile(mainCellRoot, "foo/BUCK", "load('other//:a/b.bzl', 'x')");
    VirtualFile targetFile = makeFile(otherCellRoot, "a/b.bzl", "x=1");
    PsiElement sourceElement = findStringInFile("other//:a/b.bzl", sourceFile);

    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertEquals(toPsiFile(targetFile), actualTarget);
  }

  @Test
  public void testResolveBuckTargetToRuleInSameBuckPackage() throws IOException {
    VirtualFile sourceFile =
        makeFile(
            mainCellRoot,
            "foo/BUCK",
            "genrule(name='a', deps=[':b'])",
            "genrule(name='b', deps=[':c'])",
            "genrule(name='c')");
    PsiElement sourceElement = findStringInFile(":b", sourceFile);
    PsiElement targetString = findStringInFile("b", sourceFile);
    PsiElement expectedTarget = PsiTreeUtil.getParentOfType(targetString, BuckFunctionCall.class);

    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertEquals(expectedTarget, actualTarget);
  }

  @Test
  public void testResolveBuckTargetToRuleInDifferentPackageSameCellForDefaultCell()
      throws IOException {
    VirtualFile sourceFile =
        makeFile(mainCellRoot, "foo/BUCK", "genrule(name='whatever', deps=['//bar:b'])");
    VirtualFile targetFile =
        makeFile(
            mainCellRoot,
            "bar/BUCK",
            "genrule(name='a')",
            "genrule(name='b')",
            "genrule(name='c')");
    PsiElement sourceElement = findStringInFile("//bar:b", sourceFile);
    PsiElement targetString = findStringInFile("b", targetFile);
    PsiElement expectedTarget = PsiTreeUtil.getParentOfType(targetString, BuckFunctionCall.class);

    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertEquals(expectedTarget, actualTarget);
  }

  @Test
  public void testResolveBuckTargetToRuleInDifferentPackageSameCellForNondefaultCell()
      throws IOException {
    VirtualFile sourceFile =
        makeFile(otherCellRoot, "foo/BUCK", "genrule(name='whatever', deps=['//bar:b'])");
    VirtualFile targetFile =
        makeFile(
            otherCellRoot,
            "bar/BUCK",
            "genrule(name='a')",
            "genrule(name='b')",
            "genrule(name='c')");
    PsiElement sourceElement = findStringInFile("//bar:b", sourceFile);
    PsiElement targetString = findStringInFile("b", targetFile);
    PsiElement expectedTarget = PsiTreeUtil.getParentOfType(targetString, BuckFunctionCall.class);

    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertEquals(expectedTarget, actualTarget);
  }

  @Test
  public void testResolveBuckTargetToRuleInDifferentCell() throws IOException {
    VirtualFile sourceFile =
        makeFile(mainCellRoot, "foo/BUCK", "genrule(name='whatever', deps=['other//a:b'])");
    VirtualFile targetFile =
        makeFile(
            otherCellRoot, "a/BUCK", "genrule(name='a')", "genrule(name='b')", "genrule(name='c')");
    PsiElement sourceElement = findStringInFile("other//a:b", sourceFile);
    PsiElement targetString = findStringInFile("b", targetFile);
    PsiElement expectedTarget = PsiTreeUtil.getParentOfType(targetString, BuckFunctionCall.class);

    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertEquals(expectedTarget, actualTarget);
  }

  @Test
  public void testResolveBuckTargetToBuckFileWhenCantFindTargetRuleInFile() throws IOException {
    VirtualFile sourceFile =
        makeFile(mainCellRoot, "foo/BUCK", "genrule(name='whatever', deps=['other//a:missing'])");
    VirtualFile targetFile = makeFile(otherCellRoot, "a/BUCK", "anything()");
    PsiElement sourceElement = findStringInFile("other//a:missing", sourceFile);

    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertEquals(toPsiFile(targetFile), actualTarget);
  }

  @Test
  public void testResolveGotoTargetPatternForPackage() throws IOException {
    VirtualFile sourceFile =
        makeFile(mainCellRoot, "foo/BUCK", "genrule(name='whatever', deps=['other//a:'])");
    VirtualFile targetFile = makeFile(otherCellRoot, "a/BUCK", "anything()");
    PsiElement sourceElement = findStringInFile("other//a:", sourceFile);

    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertEquals(toPsiFile(targetFile), actualTarget);
  }

  @Test
  public void testResolveGotoTargetPatternForDirectoryWithNoBuildFile() throws IOException {
    VirtualFile sourceFile =
        makeFile(mainCellRoot, "foo/BUCK", "genrule(name='whatever', deps=['other//a:'])");
    makeDirectory(otherCellRoot, "a");
    PsiElement sourceElement = findStringInFile("other//a:", sourceFile);

    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertNull(actualTarget);
  }

  @Test
  public void testResolveGotoTargetPatternForRecursivePackage() throws IOException {
    VirtualFile sourceFile =
        makeFile(mainCellRoot, "foo/BUCK", "genrule(name='whatever', deps=['other//a/...'])");
    VirtualFile targetDirectory = makeDirectory(otherCellRoot, "a");
    PsiElement sourceElement = findStringInFile("other//a/...", sourceFile);

    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertEquals(getPsiManager().findDirectory(targetDirectory), actualTarget);
  }

  @Test
  public void testResolveGotoTargetPatternForMissingRecursivePackage() throws IOException {
    VirtualFile sourceFile =
        makeFile(mainCellRoot, "foo/BUCK", "genrule(name='whatever', deps=['other//a/...'])");
    PsiElement sourceElement = findStringInFile("other//a/...", sourceFile);
    assertNotNull("Test precondition", sourceElement);
    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertNull(actualTarget);
  }

  @Test
  public void testResolveGotoFile() throws IOException {
    VirtualFile sourceFile =
        makeFile(mainCellRoot, "foo/BUCK", "genrule(name='whatever', srcs=['bar/Baz.java'])");
    VirtualFile targetFile = makeFile(mainCellRoot, "foo/bar/Baz.java", "class Baz {}");
    PsiElement sourceElement = findStringInFile("bar/Baz.java", sourceFile);

    PsiElement actualTarget = buckGotoProvider.getGotoDeclarationTarget(sourceElement);
    assertEquals(toPsiFile(targetFile), actualTarget);
  }
}
