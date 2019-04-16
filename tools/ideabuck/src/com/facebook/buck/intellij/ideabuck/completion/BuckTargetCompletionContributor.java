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

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.facebook.buck.intellij.ideabuck.api.BuckCellManager.Cell;
import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.facebook.buck.intellij.ideabuck.lang.BuckFileType;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadCall;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadTargetArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckString;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.facebook.buck.intellij.ideabuck.util.BuckPsiUtils;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Map;
import java.util.Optional;
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
    String openingQuote;
    if (BuckPsiUtils.hasElementType(position, BuckTypes.APOSTROPHED_STRING)) {
      openingQuote = "'";
    } else if (BuckPsiUtils.hasElementType(position, BuckTypes.APOSTROPHED_RAW_STRING)) {
      openingQuote = "r'";
    } else if (BuckPsiUtils.hasElementType(position, BuckTypes.TRIPLE_APOSTROPHED_STRING)) {
      openingQuote = "'''";
    } else if (BuckPsiUtils.hasElementType(position, BuckTypes.TRIPLE_APOSTROPHED_RAW_STRING)) {
      openingQuote = "r'''";
    } else if (BuckPsiUtils.hasElementType(position, BuckTypes.QUOTED_STRING)) {
      openingQuote = "\"";
    } else if (BuckPsiUtils.hasElementType(position, BuckTypes.QUOTED_RAW_STRING)) {
      openingQuote = "r\"";
    } else if (BuckPsiUtils.hasElementType(position, BuckTypes.TRIPLE_QUOTED_STRING)) {
      openingQuote = "\"\"\"";
    } else if (BuckPsiUtils.hasElementType(position, BuckTypes.TRIPLE_QUOTED_RAW_STRING)) {
      openingQuote = "r\"\"\"";
    } else {
      return;
    }
    Project project = position.getProject();
    VirtualFile virtualFile = psiFile.getVirtualFile();
    String positionStringWithQuotes = position.getText();
    String prefix =
        positionStringWithQuotes.substring(
            openingQuote.length(), parameters.getOffset() - position.getTextOffset());
    if (BuckPsiUtils.findAncestorWithType(position, BuckTypes.LOAD_TARGET_ARGUMENT) != null) {
      // Inside a load target, extension files are "@this//syntax/points:to/files.bzl"
      if (prefix.startsWith("@")) {
        prefix = prefix.substring(1);
      }
      doCellNames(position, prefix, result);
      doTargetsForRelativeExtensionFile(virtualFile, prefix, result);
      doPathsForFullyQualifiedExtensionFile(virtualFile, project, prefix, result);
      doTargetsForFullyQualifiedExtensionFile(virtualFile, project, prefix, result);
    } else if (BuckPsiUtils.findAncestorWithType(position, BuckTypes.LOAD_ARGUMENT) != null) {
      doSymbolsFromExtensionFile(virtualFile, project, position, prefix, result);
    } else {
      if (prefix.startsWith("@")) {
        prefix = prefix.substring(1);
      }
      doCellNames(position, prefix, result);
      doTargetsForRelativeBuckTarget(psiFile, prefix, result);
      doPathsForFullyQualifiedBuckTarget(virtualFile, project, prefix, result);
      doTargetsForFullyQualifiedBuckTarget(virtualFile, project, prefix, result);
    }
  }

  private void addResultForTarget(CompletionResultSet result, String name) {
    result.addElement(LookupElementBuilder.create(name).withIcon(BuckIcons.FILE_TYPE));
  }

  private void addResultForFile(CompletionResultSet result, VirtualFile file, String name) {
    result.addElement(LookupElementBuilder.create(name).withIcon(file.getFileType().getIcon()));
  }

  private void doCellNames(PsiElement position, String prefix, CompletionResultSet result) {
    if (prefix.contains("/")) {
      return; // already beyond a cell name
    }
    Project project = position.getProject();
    BuckCellManager.getInstance(project)
        .getCells()
        .forEach(
            cell ->
                cell.getName()
                    .filter(name -> name.startsWith(prefix))
                    .ifPresent(name -> addResultForTarget(result, name + "//")));
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
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    VirtualFile targetDirectory =
        BuckTargetPattern.parse(cellName + "//" + cellPath)
            .flatMap(p -> buckTargetLocator.resolve(sourceVirtualFile, p))
            .flatMap(buckTargetLocator::findVirtualFileForTargetPattern)
            .orElse(null);
    if (targetDirectory == null) {
      return;
    }
    String partialPrefix;
    if ("".equals(cellName)) {
      partialPrefix = cellPath;
    } else {
      partialPrefix = cellName + "//" + cellPath;
    }
    String partial = matcher.group("partial");
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
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    VirtualFile targetDirectory =
        BuckTargetPattern.parse(cellName + "//" + cellPath + "/" + extPath)
            .flatMap(p -> buckTargetLocator.resolve(sourceVirtualFile, p))
            .flatMap(buckTargetLocator::findVirtualFileForTargetPattern)
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
    String partial = matcher.group("partial");
    for (VirtualFile child : targetDirectory.getChildren()) {
      String name = child.getName();
      if (!child.isDirectory() && name.startsWith(partial) && name.endsWith(".bzl")) {
        addResultForFile(result, child, partialPrefix + name);
      }
    }
  }

  private void doSymbolsFromExtensionFile(
      VirtualFile sourceFile,
      Project project,
      PsiElement position,
      String prefix,
      CompletionResultSet result) {
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    Optional.of(position)
        .map(e -> PsiTreeUtil.getParentOfType(e, BuckLoadCall.class))
        .map(BuckLoadCall::getLoadTargetArgument)
        .map(BuckLoadTargetArgument::getString)
        .map(BuckString::getValue)
        .flatMap(BuckTarget::parse)
        .flatMap(target -> buckTargetLocator.resolve(sourceFile, target))
        .flatMap(buckTargetLocator::findVirtualFileForExtensionFile)
        .map(PsiManager.getInstance(project)::findFile)
        .map(psiFile -> BuckPsiUtils.findSymbolsInPsiTree(psiFile, prefix))
        .map(Map::keySet)
        .ifPresent(
            symbols ->
                symbols.stream()
                    .filter(symbol -> !symbol.startsWith("_")) // do not show private symbols
                    .forEach(symbol -> addResultForTarget(result, symbol)));
  }

  /** Autocomplete buck targets within this buck file, as in ":targ" => ":target". */
  private void doTargetsForRelativeBuckTarget(
      PsiFile psiFile, String prefixToAutocomplete, CompletionResultSet result) {
    if (!prefixToAutocomplete.startsWith(":")) {
      return;
    }
    // Autocomplete a target in *this* file
    String targetPrefix = prefixToAutocomplete.substring(1);
    Map<String, ?> targetsInPsiTree = BuckPsiUtils.findTargetsInPsiTree(psiFile, targetPrefix);
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
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);

    BuckTargetPattern targetPattern =
        BuckTargetPattern.parse(cellName + "//" + cellPath)
            .flatMap(p -> buckTargetLocator.resolve(sourceFile, p))
            .orElse(null);
    if (targetPattern == null) {
      return;
    }
    BuckCellManager buckCellManager = BuckCellManager.getInstance(project);
    Cell cell =
        buckCellManager.findCellByName(targetPattern.getCellName().orElse(cellName)).orElse(null);
    if (cell == null) {
      return;
    }

    VirtualFile targetDirectory =
        buckTargetLocator.findVirtualFileForTargetPattern(targetPattern).orElse(null);
    if (targetDirectory == null) {
      return;
    }
    String partialPrefix;
    if ("".equals(cellName)) {
      partialPrefix = cellPath;
    } else {
      partialPrefix = cellName + "//" + cellPath;
    }
    String partial = matcher.group("partial");
    for (VirtualFile child : targetDirectory.getChildren()) {
      String name = child.getName();
      if (!name.startsWith(partial)) {
        continue;
      }
      if (child.isDirectory()) {
        VirtualFile childBuckFile = child.findChild(cell.getBuildfileName());
        if (childBuckFile != null && childBuckFile.exists()) {
          addResultForFile(result, childBuckFile, partialPrefix + name + ":");
        }
        addResultForFile(result, child, partialPrefix + name + "/");
      }
    }
  }

  private void doTargetsForFullyQualifiedBuckTarget(
      VirtualFile sourceFile,
      Project project,
      String prefixToAutocomplete,
      CompletionResultSet result) {
    BuckTargetPattern pattern = BuckTargetPattern.parse(prefixToAutocomplete).orElse(null);
    if (pattern == null) {
      return;
    }
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    VirtualFile targetBuildFile =
        buckTargetLocator
            .resolve(sourceFile, pattern)
            .flatMap(buckTargetLocator::findVirtualFileForTargetPattern)
            .orElse(null);
    if (targetBuildFile == null) {
      return;
    }
    PsiFile targetPsiFile = PsiManager.getInstance(project).findFile(targetBuildFile);
    if (targetPsiFile == null) {
      return;
    }
    Map<String, ?> targetsInPsiTree =
        BuckPsiUtils.findTargetsInPsiTree(targetPsiFile, pattern.getRuleName().orElse(""));
    String cellName = pattern.getCellName().orElse(null);
    String cellPath = pattern.getCellPath().orElse("");
    for (String name : targetsInPsiTree.keySet()) {
      String completion;
      if (cellName == null) {
        completion = cellPath + ":" + name;
      } else {
        completion = cellName + "//" + cellPath + ":" + name;
      }
      addResultForTarget(result, completion);
    }
  }
}
