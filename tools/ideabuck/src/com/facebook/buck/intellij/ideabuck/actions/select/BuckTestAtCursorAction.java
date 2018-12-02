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
package com.facebook.buck.intellij.ideabuck.actions.select;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.Nullable;

/** Base class for running tests at the cursor. */
public abstract class BuckTestAtCursorAction extends AbstractBuckTestAction {

  private Logger logger = Logger.getInstance(BuckTestAtCursorAction.class);

  /** Walk up the Psi tree to find the nearest method or class runnable as a test. */
  @Nullable
  private PsiElement findNearestTestElement(
      @Nullable PsiFile psiFile, @Nullable PsiElement psiElement) {
    PsiElement currentElement = psiElement;
    boolean seenAtLeastOneClass = false;
    while (currentElement != null) {
      if (currentElement instanceof PsiMethod) {
        PsiMethod psiMethod = (PsiMethod) currentElement;
        PsiClass psiClass = ((PsiMethod) currentElement).getContainingClass();
        if (psiClass != null && BuckTestDetector.isTestMethod(psiMethod)) {
          return psiMethod;
        }
      } else if (currentElement instanceof PsiClass) {
        seenAtLeastOneClass = true;
        PsiClass psiClass = (PsiClass) currentElement;
        if (BuckTestDetector.isTestClass(psiClass)) {
          return psiClass;
        }
      }
      currentElement = currentElement.getParent();
    }
    if (!seenAtLeastOneClass && psiFile != null) {
      for (PsiClass psiClass : PsiTreeUtil.findChildrenOfType(psiFile, PsiClass.class)) {
        if (BuckTestDetector.isTestClass(psiClass)) {
          return psiClass;
        }
      }
    }
    return null;
  }

  private void findTestAndDo(AnActionEvent anActionEvent, BiConsumer<PsiClass, PsiMethod> action) {
    PsiElement psiElement = anActionEvent.getData(CommonDataKeys.PSI_ELEMENT);
    PsiFile psiFile = anActionEvent.getData(LangDataKeys.PSI_FILE);
    if (psiElement == null) {
      Editor editor = anActionEvent.getData(CommonDataKeys.EDITOR);
      if (editor != null && psiFile != null) {
        psiElement = psiFile.findElementAt(editor.getCaretModel().getOffset());
      }
    }
    if (psiFile == null && psiElement != null) {
      psiFile = psiElement.getContainingFile();
    }
    if (psiElement != null) {
      PsiElement testElement = findNearestTestElement(psiFile, psiElement);
      if (testElement instanceof PsiClass) {
        PsiClass psiClass = (PsiClass) testElement;
        action.accept(psiClass, null);
        return;
      } else if (testElement instanceof PsiMethod) {
        PsiMethod psiMethod = (PsiMethod) testElement;
        action.accept(psiMethod.getContainingClass(), psiMethod);
        return;
      }
    }
    action.accept(null, null);
  }

  @Override
  public void update(AnActionEvent anActionEvent) {
    findTestAndDo(
        anActionEvent,
        (psiClass, psiMethod) -> {
          updatePresentation(anActionEvent.getPresentation(), psiClass, psiMethod);
        });
  }

  @Override
  public void actionPerformed(AnActionEvent anActionEvent) {
    findTestAndDo(
        anActionEvent,
        (psiClass, psiMethod) -> {
          if (psiClass != null) {
            setupAndExecuteTestConfiguration(psiClass, psiMethod);
          } else {
            logger.debug("Can't perform action: no test element under cursor");
          }
        });
  }

  /** Runs the test method/class at the cursor using {@code buck test}. */
  public static class Run extends BuckTestAtCursorAction {
    @Override
    boolean isDebug() {
      return false;
    }
  }

  /** Debugs the test method/class at the cursor using {@code buck test}. */
  public static class Debug extends BuckTestAtCursorAction {
    @Override
    boolean isDebug() {
      return true;
    }
  }
}
