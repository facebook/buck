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
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.Nullable;

/** Base class to be extended by Intentions that fixes Buck and IntelliJ dependencies. */
public abstract class AbstractBuckAddDependencyIntention extends BaseIntentionAction {

  final Project project;

  // Fields pertaining to the PsiReference in the file being edited
  final PsiReference reference;
  final VirtualFile editBuildFile;
  final VirtualFile editSourceFile;
  final BuckTarget editSourceTarget;
  final Module editModule;

  // Fields pertaining to the dependency that should be resolved/imported
  @Nullable final PsiClass psiClass;

  // Should be set by subclass
  BuckTarget editTarget;
  BuckTarget importSourceTarget;
  BuckTarget importTarget;

  @Nullable final BuckAddImportAction addImportAction;

  public AbstractBuckAddDependencyIntention(CommonAddDependencyDataWrapper wrapper) {
    project = wrapper.project;
    reference = wrapper.reference;
    editBuildFile = wrapper.editBuildFile;
    editSourceFile = wrapper.editSourceFile;
    editSourceTarget = wrapper.editSourceTarget;
    editModule = wrapper.editModule;
    psiClass = wrapper.psiClass;
    addImportAction = wrapper.addImportAction;
  }
}
