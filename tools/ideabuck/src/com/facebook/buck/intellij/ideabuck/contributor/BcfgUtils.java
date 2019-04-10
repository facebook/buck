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
package com.facebook.buck.intellij.ideabuck.contributor;

import com.facebook.buck.intellij.ideabuck.lang.BcfgFile;
import com.facebook.buck.intellij.ideabuck.lang.BcfgFileType;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgProperty;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgSection;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.indexing.FileBasedIndex;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One-off utilities for working with {@code .buckconfig} files and elements that don't currently
 * have a better home.
 */
public class BcfgUtils {

  /** Returns the known buckconfig files in the given {@link Project}. */
  public static Stream<BcfgFile> findBcfgFiles(Project project) {
    // TODO:  Replace this with a method that returns the buckconfig
    // files for a given cell, accounting for included config files and
    // Buck's rules of precedence, as per:
    //   https://buckbuild.com/files-and-dirs/buckconfig.html#config-precedence
    PsiManager psiManager = PsiManager.getInstance(project);
    GlobalSearchScope searchScope = GlobalSearchScope.allScope(project);
    return FileBasedIndex.getInstance()
        .getContainingFiles(FileTypeIndex.NAME, BcfgFileType.INSTANCE, searchScope).stream()
        .map(psiManager::findFile)
        .map(BcfgFile.class::cast)
        .filter(Objects::nonNull);
  }

  /** Returns all known properties in the given {@link Project}. */
  public static Stream<BcfgProperty> findProperties(Project project) {
    return findBcfgFiles(project)
        .map(psiFile -> PsiTreeUtil.getChildrenOfType(psiFile, BcfgProperty.class))
        .filter(Objects::nonNull)
        .flatMap(Stream::of);
  }

  /** Returns all known sections in the given {@link Project}. */
  public static Stream<BcfgSection> findSections(Project project) {
    return findBcfgFiles(project)
        .map(psiFile -> PsiTreeUtil.getChildrenOfType(psiFile, BcfgSection.class))
        .filter(Objects::nonNull)
        .flatMap(Stream::of);
  }
}
