/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.completion;

import com.facebook.buck.intellij.ideabuck.config.BuckCell;
import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.facebook.buck.intellij.ideabuck.file.BuckFileType;
import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPsiUtils;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.facebook.buck.intellij.ideabuck.util.BuckCellFinder;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons.Nodes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

/**
 * Auto-completion for buck targets of the form {@code cellname//path/to:target} and the load syntax
 * of the form {@code @cellname//path/to:file.bzl}.
 */
public class BuckTargetCompletionContributor extends CompletionContributor {

  @Override
  public void fillCompletionVariants(
      @NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
    PsiFile psiFile = parameters.getOriginalFile();
    if (!BuckFileType.INSTANCE.equals(psiFile.getFileType())) {
      return;
    }
    PsiElement position = parameters.getPosition();
    String quotes;
    if (BuckPsiUtils.hasElementType(position, BuckTypes.SINGLE_QUOTED_STRING)) {
      quotes = "'";
    } else if (BuckPsiUtils.hasElementType(position, BuckTypes.DOUBLE_QUOTED_STRING)) {
      quotes = "\"";
    } else if (BuckPsiUtils.hasElementType(position, BuckTypes.SINGLE_QUOTED_DOC_STRING)) {
      quotes = "'''";
    } else if (BuckPsiUtils.hasElementType(position, BuckTypes.DOUBLE_QUOTED_DOC_STRING)) {
      quotes = "\"\"\"";
    } else {
      return;
    }
    Project project = position.getProject();
    VirtualFile virtualFile = psiFile.getVirtualFile();
    String positionStringWithQuotes = position.getText();
    String prefix =
        positionStringWithQuotes.substring(
            quotes.length(), parameters.getOffset() - position.getTextOffset());
    if (prefix.startsWith("@")) {
      prefix = prefix.substring(1);
    }
    doCellNames(position, prefix, result);
    if (BuckPsiUtils.findAncestorWithType(position, BuckTypes.LOAD_TARGET_ARGUMENT) != null) {
      // Inside a load target, extension files are "@this//syntax/points:to/files.bzl"
      doTargetsForRelativeExtensionFile(virtualFile, prefix, result);
      doPathsForFullyQualifiedExtensionFile(virtualFile, project, prefix, result);
      doTargetsForFullyQualifiedExtensionFile(virtualFile, project, prefix, result);
    } else {
      doTargetsForRelativeBuckTarget(psiFile, prefix, result);
      doPathsForFullyQualifiedBuckTarget(virtualFile, project, prefix, result);
      doTargetsForFullyQualifiedBuckTarget(virtualFile, project, prefix, result);
    }
  }

  private void addResultForTarget(CompletionResultSet result, String name) {
    result.addElement(LookupElementBuilder.create(name).withIcon(BuckIcons.FILE_TYPE));
  }

  private void addResultForFile(CompletionResultSet result, VirtualFile file, String name) {
    if (file.isDirectory()) {
      result.addElement(LookupElementBuilder.create(name).withIcon(Nodes.Folder));
    } else {
      // TODO: get filetype for file
      result.addElement(LookupElementBuilder.create(name).withIcon(BuckIcons.FILE_TYPE));
    }
  }

  private void doCellNames(PsiElement position, String prefix, CompletionResultSet result) {
    if (prefix.contains("/")) {
      return; // already beyond a cell name
    }
    Project project = position.getProject();
    BuckProjectSettingsProvider buckProjectSettingsProvider =
        BuckProjectSettingsProvider.getInstance(project);
    for (String cellName : buckProjectSettingsProvider.getCellNames()) {
      if (cellName.startsWith(prefix)) {
        addResultForTarget(result, cellName + "//");
      }
    }
  }

  private static final Pattern RELATIVE_TARGET_TO_EXTENSION_FILE_PATTERN =
      Pattern.compile(":(?<path>([^:/]+/)*)(?<partial>[^:/]*)");

  /**
   * Autocomplete when inside the target part of a relative-qualified extension file, e.g. {@code
   * ":ex" => ":ext.bzl"}.
   */
  private void doTargetsForRelativeExtensionFile(
      VirtualFile virtualFile, String prefixToAutocomplete, CompletionResultSet result) {
    if (!prefixToAutocomplete.startsWith(":")) {
      return;
    }
    Matcher matcher = RELATIVE_TARGET_TO_EXTENSION_FILE_PATTERN.matcher(prefixToAutocomplete);
    if (!matcher.matches()) {
      return;
    }
    String path = matcher.group("path");
    VirtualFile dir = virtualFile.findFileByRelativePath("../" + path);
    if (dir == null || !dir.isDirectory()) {
      return;
    }
    String partial = matcher.group("partial");
    for (VirtualFile child : dir.getChildren()) {
      String name = child.getName();
      if (!name.startsWith(partial)) {
        continue;
      }
      if (child.isDirectory()) {
        addResultForFile(result, child, path + name + "/");
      } else if (name.endsWith(".bzl")) {
        addResultForFile(result, child, path + name);
      }
    }
  }

  private static final Pattern PATH_TO_EXTENSION_FILE_PATTERN =
      Pattern.compile("(?<cell>[^/:]*)//(?<path>([^:/]+/)*)(?<partial>[^:/]*)");

  /**
   * Autocomplete when inside the path part of a fully-qualified extension file, e.g. {@code
   * "@cell//path/t" => "@cell//path/to/" or "@cell//path/to:ext.bzl"}.
   */
  private void doPathsForFullyQualifiedExtensionFile(
      VirtualFile sourceVirtualFile,
      Project project,
      String prefixToAutocomplete,
      CompletionResultSet result) {
    Matcher matcher = PATH_TO_EXTENSION_FILE_PATTERN.matcher(prefixToAutocomplete);
    if (!matcher.matches()) {
      return;
    }
    String cellName = matcher.group("cell");
    String cellPath = matcher.group("path");
    String partial = matcher.group("partial");
    BuckCellFinder buckCellFinder = BuckCellFinder.getInstance(project);
    VirtualFile targetDirectory =
        buckCellFinder.resolveCellPath(sourceVirtualFile, cellName + "//" + cellPath).orElse(null);
    if (targetDirectory == null) {
      return;
    }
    String partialPrefix;
    if ("".equals(cellName)) {
      partialPrefix = cellPath;
    } else {
      partialPrefix = cellName + "//" + cellPath;
    }
    for (VirtualFile child : targetDirectory.getChildren()) {
      String name = child.getName();
      if (!name.startsWith(partial)) {
        continue;
      }
      if (child.isDirectory()) {
        doTargetsForFullyQualifiedExtensionFile(
            child, project, cellName + "//" + cellPath + name + ":", result);
        if (Stream.of(child.getChildren()).anyMatch(VirtualFile::isDirectory)) {
          addResultForFile(result, child, partialPrefix + name + "/");
        }
      }
    }
  }

  private static final Pattern TARGET_TO_EXTENSION_FILE_PATTERN =
      Pattern.compile("(?<cell>[^/:]*)//(?<path>[^:]+):(?<extpath>([^:/]+/)*)(?<partial>[^:/]*)");

  /**
   * Autocomplete when inside the target part of a fully-qualified extension file, e.g. {@code
   * "@cell//path/to:ex" => "@cell//path/to:ext.bzl"}.
   */
  private void doTargetsForFullyQualifiedExtensionFile(
      VirtualFile sourceVirtualFile,
      Project project,
      String prefixToAutocomplete,
      CompletionResultSet result) {
    Matcher matcher = TARGET_TO_EXTENSION_FILE_PATTERN.matcher(prefixToAutocomplete);
    if (!matcher.matches()) {
      return;
    }
    String cellName = matcher.group("cell");
    String cellPath = matcher.group("path");
    String extPath = matcher.group("extpath");
    String partial = matcher.group("partial");
    BuckCellFinder buckCellFinder = BuckCellFinder.getInstance(project);
    VirtualFile targetDirectory =
        buckCellFinder
            .resolveCellPath(sourceVirtualFile, cellName + "//" + cellPath + "/" + extPath)
            .orElse(null);
    if (targetDirectory == null) {
      return;
    }
    String partialPrefix;
    if ("".equals(cellName)) {
      partialPrefix = cellPath + ":" + extPath;
    } else {
      partialPrefix = cellName + "//" + cellPath + ":" + extPath;
    }
    for (VirtualFile child : targetDirectory.getChildren()) {
      String name = child.getName();
      if (name.startsWith(partial) && name.endsWith(".bzl")) {
        addResultForFile(result, child, partialPrefix + name);
      }
    }
  }

  /** Autocomplete buck targets within this buck file, as in ":targ" => ":target". */
  private void doTargetsForRelativeBuckTarget(
      PsiFile psiFile, String prefixToAutocomplete, CompletionResultSet result) {
    if (!prefixToAutocomplete.startsWith(":")) {
      return;
    }
    // Autocomplete a target in *this* file
    String targetPrefix = prefixToAutocomplete.substring(1);
    Map<String, PsiElement> targetsInPsiTree =
        BuckPsiUtils.findTargetsInPsiTree(psiFile, targetPrefix);
    for (String name : targetsInPsiTree.keySet()) {
      addResultForTarget(result, name);
    }
  }

  private static final Pattern PATH_TO_BUCK_TARGET_PATTERN =
      Pattern.compile("(?<cell>[^/:]*)//(?<path>([^:]+/)*)(?<partial>[^:/]*)");

  /**
   * Autocomplete when inside the path part of a fully-qualified buck target, e.g. {@code
   * "@cell//path/to" => "@cell//path/to/dir", "@cell//path/to:target", "@cell//path/tolongerdir"}.
   */
  private void doPathsForFullyQualifiedBuckTarget(
      VirtualFile sourceFile,
      Project project,
      String prefixToAutocomplete,
      CompletionResultSet result) {
    Matcher matcher = PATH_TO_BUCK_TARGET_PATTERN.matcher(prefixToAutocomplete);
    if (!matcher.matches()) {
      return;
    }
    String cellName = matcher.group("cell");
    String cellPath = matcher.group("path");
    String partial = matcher.group("partial");
    BuckCellFinder buckCellFinder = BuckCellFinder.getInstance(project);
    BuckCell buckCell = buckCellFinder.findBuckCell(sourceFile, cellName).orElse(null);
    if (buckCell == null) {
      return;
    }
    VirtualFile targetDir =
        buckCellFinder.resolveCellPath(sourceFile, cellName + "//" + cellPath).orElse(null);
    if (targetDir == null) {
      return;
    }
    String partialPrefix;
    if ("".equals(cellName)) {
      partialPrefix = cellPath;
    } else {
      partialPrefix = cellName + "//" + cellPath;
    }
    for (VirtualFile child : targetDir.getChildren()) {
      String name = child.getName();
      if (!name.startsWith(partial)) {
        continue;
      }
      if (child.isDirectory()) {
        VirtualFile childBuckFile = child.findChild(buckCell.getBuildFileName());
        if (childBuckFile != null && childBuckFile.exists()) {
          addResultForFile(result, childBuckFile, partialPrefix + name + ":");
        }
        addResultForFile(result, child, partialPrefix + name + "/");
      }
    }
  }

  private static final Pattern TARGET_TO_BUCK_TARGET_PATTERN =
      Pattern.compile("(?<cell>[^/:]*)//(?<path>[^:]+):(?<partial>[^:]*)");

  private void doTargetsForFullyQualifiedBuckTarget(
      VirtualFile sourceFile,
      Project project,
      String prefixToAutocomplete,
      CompletionResultSet result) {
    Matcher matcher = TARGET_TO_BUCK_TARGET_PATTERN.matcher(prefixToAutocomplete);
    if (!matcher.matches()) {
      return;
    }
    String cellName = matcher.group("cell");
    String cellPath = matcher.group("path");
    String partial = matcher.group("partial");
    BuckCellFinder buckCellFinder = BuckCellFinder.getInstance(project);
    VirtualFile targetVirtualFile =
        buckCellFinder.findBuckTargetFile(sourceFile, prefixToAutocomplete).orElse(null);
    if (targetVirtualFile == null) {
      return;
    }
    PsiFile targetPsiFile = PsiManager.getInstance(project).findFile(targetVirtualFile);
    Map<String, PsiElement> targetsInPsiTree =
        BuckPsiUtils.findTargetsInPsiTree(targetPsiFile, partial);
    for (String name : targetsInPsiTree.keySet()) {
      String completion;
      if ("".equals(cellName)) {
        completion = cellPath + ":" + name;
      } else {
        completion = cellName + "//" + cellPath + ":" + name;
      }
      addResultForTarget(result, completion);
    }
  }
}
