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

package com.facebook.buck.intellij.ideabuck.autodeps;

import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.intellij.codeInsight.daemon.impl.actions.AddImportAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiReference;
import javax.annotation.Nullable;

/** Factory class for creating dependency fixes for buck */
public class BuckAddDependencyIntentionFactory {

  /**
   * Creates an {@link com.intellij.codeInsight.intention.IntentionAction} that will create an
   * dependency edge in both the Buck target graph and IntelliJ module graph from the nodes for the
   * given reference element to those of the given psiClass.
   *
   * <p>Note that this intention can fail to be created if either side of the edge cannot be
   * resolved to a buck file in a buck cell, in which case this method returns null. Also, invoking
   * this intention may fail to create edges in either the Buck target graph or the IntelliJ module
   * graph (or both).
   */
  @Nullable
  public static BuckAddDependencyIntention createAddModuleDependencyIntention(
      PsiReference reference, PsiClass psiClass) {
    return createAddModuleDependencyIntention(
        reference,
        psiClass,
        new BuckAddImportAction() {
          @Override
          public boolean execute(
              Project project, PsiReference reference, Editor editor, PsiClass psiClass) {
            return new AddImportAction(project, reference, editor, psiClass).execute();
          }
        });
  }

  @Nullable
  public static BuckAddDependencyIntention createAddModuleDependencyIntention(
      PsiReference reference, PsiClass psiClass, BuckAddImportAction importAction) {
    return createAddModuleDependencyIntention(
        reference, psiClass.getContainingFile().getVirtualFile(), psiClass, importAction);
  }

  @Nullable
  public static BuckAddDependencyIntention createAddModuleDependencyIntention(
      PsiReference reference,
      VirtualFile importSourceFile,
      @Nullable PsiClass psiClass,
      BuckAddImportAction addImportAction) {
    Project project = reference.getElement().getProject();
    BuckTargetLocator buckTargetLocator = BuckTargetLocator.getInstance(project);
    ProjectFileIndex projectFileIndex = ProjectFileIndex.getInstance(project);
    CommonAddDependencyDataWrapper wrapper =
        createCommonAddDependencyDataWrapper(
            reference, psiClass, addImportAction, project, buckTargetLocator, projectFileIndex);
    if (wrapper == null) {
      return null;
    }
    VirtualFile importBuildFile =
        buckTargetLocator.findBuckFileForVirtualFile(importSourceFile).orElse(null);
    if (importBuildFile == null || importBuildFile.equals(wrapper.editBuildFile)) {
      return null;
    }
    Module importModule = projectFileIndex.getModuleForFile(importSourceFile);
    if (importModule == null) {
      return null;
    }
    BuckTarget importSourceTarget =
        buckTargetLocator
            .findTargetPatternForVirtualFile(importSourceFile)
            .flatMap(BuckTargetPattern::asBuckTarget)
            .orElse(null);
    if (importSourceTarget == null) {
      return null;
    }
    return new BuckAddDependencyIntention(
        wrapper, importSourceFile, importBuildFile, importModule, importSourceTarget);
  }

  /** Returns a wrapper that contains shared fields between Buck actions that add dependencies */
  @Nullable
  private static CommonAddDependencyDataWrapper createCommonAddDependencyDataWrapper(
      PsiReference reference,
      @Nullable PsiClass psiClass,
      BuckAddImportAction addImportAction,
      Project project,
      BuckTargetLocator buckTargetLocator,
      ProjectFileIndex projectFileIndex) {
    VirtualFile editSourceFile = reference.getElement().getContainingFile().getVirtualFile();
    if (editSourceFile == null) {
      return null;
    }
    VirtualFile editBuildFile =
        buckTargetLocator.findBuckFileForVirtualFile(editSourceFile).orElse(null);
    if (editBuildFile == null) {
      return null;
    }
    Module editModule = projectFileIndex.getModuleForFile(editSourceFile);
    if (editModule == null) {
      return null;
    }
    BuckTarget editSourceTarget =
        buckTargetLocator
            .findTargetPatternForVirtualFile(editSourceFile)
            .flatMap(BuckTargetPattern::asBuckTarget)
            .orElse(null);
    if (editSourceTarget == null) {
      return null;
    }
    return new CommonAddDependencyDataWrapper(
        project,
        reference,
        editBuildFile,
        editSourceFile,
        editSourceTarget,
        editModule,
        psiClass,
        addImportAction);
  }
}
