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

package com.facebook.buck.intellij.ideabuck.autodeps;

import com.facebook.buck.intellij.ideabuck.actions.BuckAuditOwner;
import com.facebook.buck.intellij.ideabuck.config.BuckCell;
import com.facebook.buck.intellij.ideabuck.util.BuckCellFinder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.codeInsight.daemon.impl.actions.AddImportAction;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.util.IncorrectOperationException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nls.Capitalization;
import org.jetbrains.annotations.NotNull;

/**
 * An intention that will attempt to add a dependency edge to both the Buck graph and the IntelliJ
 * module graph.
 */
public class BuckAddDependencyIntention extends BaseIntentionAction {
  private static Logger LOGGER = Logger.getInstance(BuckAddDependencyIntention.class);

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
  public static BuckAddDependencyIntention create(
      PsiJavaCodeReferenceElement referenceElement, PsiClass psiClass) {
    VirtualFile editSourceFile = referenceElement.getContainingFile().getVirtualFile();
    if (editSourceFile == null) {
      return null;
    }
    Project project = referenceElement.getProject();
    BuckCellFinder buckCellFinder = BuckCellFinder.getInstance(project);
    BuckCell editBuckCell = buckCellFinder.findBuckCell(editSourceFile).orElse(null);
    if (editBuckCell == null) {
      return null;
    }
    VirtualFile editBuckFile = buckCellFinder.findBuckFile(editSourceFile).orElse(null);
    if (editBuckFile == null) {
      return null;
    }

    VirtualFile importSourceFile = psiClass.getContainingFile().getVirtualFile();
    if (importSourceFile == null) {
      return null;
    }
    BuckCell importBuckCell = buckCellFinder.findBuckCell(importSourceFile).orElse(null);
    if (importBuckCell == null) {
      return null;
    }
    VirtualFile importBuckFile = buckCellFinder.findBuckFile(importSourceFile).orElse(null);
    if (importBuckFile == null) {
      return null;
    }
    if (importBuckFile.equals(editBuckFile)) {
      return null;
    }
    return new BuckAddDependencyIntention(
        referenceElement,
        editBuckCell,
        editBuckFile,
        editSourceFile,
        psiClass,
        importBuckCell,
        importBuckFile,
        importSourceFile);
  }

  // Fields pertaining to the element in the file being edited
  private PsiJavaCodeReferenceElement referenceElement;
  private BuckCell editBuckCell;
  private VirtualFile editBuckFile;
  private VirtualFile editSourceFile;

  // Fields pertaining to the dependency that should be resolved/imported
  private PsiClass psiClass;
  private BuckCell importBuckCell;
  private VirtualFile importBuckFile;
  private VirtualFile importSourceFile;

  BuckAddDependencyIntention(
      PsiJavaCodeReferenceElement referenceElement,
      BuckCell editBuckCell,
      VirtualFile editBuckFile,
      VirtualFile editSourceFile,
      PsiClass psiClass,
      BuckCell importBuckCell,
      VirtualFile importBuckFile,
      VirtualFile importSourceFile) {
    this.referenceElement = referenceElement;
    this.editBuckCell = editBuckCell;
    this.editBuckFile = editBuckFile;
    this.editSourceFile = editSourceFile;
    this.psiClass = psiClass;
    this.importBuckCell = importBuckCell;
    this.importBuckFile = importBuckFile;
    this.importSourceFile = importSourceFile;
    String message =
        "Add BUCK dependency on owner("
            + importSourceFile
                .getPath()
                .replace(importBuckCell.getRoot(), importBuckCell.getName() + "/")
            + ")";
    setText(message);
  }

  @Nls(capitalization = Capitalization.Sentence)
  @NotNull
  @Override
  public String getFamilyName() {
    return this.getClass().getSimpleName();
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile psiFile) {
    return true;
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, PsiFile psiFile)
      throws IncorrectOperationException {
    String msg = "Invoked for project " + project.getName() + " and file " + psiFile.getName();
    LOGGER.info(msg);
    addEdgeToBuckGraph(project);
    // If possible, add module dependency in IntelliJ
    addEdgeToIntelliJModuleGraph(project);
    // Finally, make sure the reference is imported, if necessary
    new AddImportAction(project, referenceElement, editor, psiClass).execute();
  }

  private void addEdgeToIntelliJModuleGraph(Project project) {
    ProjectFileIndex projectFileIndex = ProjectFileIndex.getInstance(project);
    Module editModule = projectFileIndex.getModuleForFile(editSourceFile);
    Module importModule = projectFileIndex.getModuleForFile(importSourceFile);
    if (editModule != null && importModule != null) {
      ModuleRootModificationUtil.updateModel(
          editModule,
          (modifiableRootModel -> {
            if (modifiableRootModel.findModuleOrderEntry(importModule) == null) {
              modifiableRootModel.addModuleOrderEntry(importModule);
            }
          }));
    }
  }

  @VisibleForTesting
  private void addEdgeToBuckGraph(@NotNull Project project) {
    BuckAuditOwner.execute(
        project,
        resultString -> {
          try {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, List<String>> pathAndTargetData =
                (Map<String, List<String>>) objectMapper.readValue(resultString, Map.class);
            String editTarget = pathAndTargetData.get(editSourceFile.getCanonicalPath()).get(0);
            String importTarget = pathAndTargetData.get(importSourceFile.getCanonicalPath()).get(0);
            if (editTarget.startsWith("//")) {
              editTarget = editBuckCell.getName() + editTarget;
            }
            if (importTarget.startsWith("//")) {
              importTarget = importBuckCell.getName() + importTarget;
            }
            BuckDeps.modifyTargetToAddDependency(editBuckFile, editTarget, importTarget);
            BuckDeps.addVisibilityToTargetForUsage(importBuckFile, importTarget, editTarget);
          } catch (IOException | NoSuchElementException e) {
            LOGGER.error("Could not parse buck audit owner results", e);
          }
        },
        editSourceFile.getCanonicalPath(),
        importSourceFile.getCanonicalPath());
  }
}
