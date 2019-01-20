/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.intellij.ideabuck.actions.select;

import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Creates runnable line markers for classes/methods that can be run by a buck test configurations.
 */
public class SelectedTestRunLineMarkerContributor extends RunLineMarkerContributor {

  @Nullable
  @Override
  public RunLineMarkerContributor.Info getInfo(@NotNull PsiElement psiElement) {
    if (psiElement.getContainingFile().getFileType() != JavaFileType.INSTANCE) {
      return null;
    }
    // Mark the PsiIdentifier so that the buck action will share the same element
    // as com.intellij.testIntegration.TestRunLineMarkerProvider
    if (psiElement instanceof PsiIdentifier) {
      PsiElement parent = psiElement.getParent();
      if (parent instanceof PsiClass) {
        PsiClass psiClass = (PsiClass) parent;
        if (BuckTestDetector.isTestClass(psiClass)) {
          return createInfo(psiClass, null);
        }
      } else if (parent instanceof PsiMethod) {
        PsiMethod psiMethod = (PsiMethod) parent;
        PsiClass psiClass = psiMethod.getContainingClass();
        if (BuckTestDetector.isTestMethod(psiMethod)) {
          return createInfo(psiClass, psiMethod);
        }
      }
    }
    return null;
  }

  private Info createInfo(PsiClass psiClass, PsiMethod psiMethod) {
    // TODO: Verify that this file is part of a buck test target
    return new Info(
        IconLoader.getIcon("/icons/buck_icon.png"),
        new AnAction[] {
          new FixedBuckTestAction(psiClass, psiMethod, false),
          new FixedBuckTestAction(psiClass, psiMethod, true),
        },
        RunLineMarkerContributor.RUN_TEST_TOOLTIP_PROVIDER);
  }

  /** Implementation of {@link AbstractBuckTestAction} that runs a fixed class/method. */
  class FixedBuckTestAction extends AbstractBuckTestAction {
    private PsiClass psiClass;
    private @Nullable PsiMethod psiMethod;
    private boolean debug;

    FixedBuckTestAction(PsiClass psiClass, @Nullable PsiMethod psiMethod, boolean debug) {
      this.psiClass = psiClass;
      this.psiMethod = psiMethod;
      this.debug = debug;
    }

    @Override
    boolean isDebug() {
      return debug;
    }

    @Override
    public void update(AnActionEvent e) {
      updatePresentation(e.getPresentation(), psiClass, psiMethod);
    }

    @Override
    public void actionPerformed(AnActionEvent anActionEvent) {
      setupAndExecuteTestConfiguration(psiClass, psiMethod);
    }
  }
}
