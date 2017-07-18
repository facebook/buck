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

import com.facebook.buck.intellij.ideabuck.configurations.TestConfigurationUtil;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiKeyword;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiTypeParameterList;
import org.jetbrains.annotations.Nullable;

/** Class denoting the lines that can create buck test configurations. */
public class SelectedTestRunLineMarkerContributor extends RunLineMarkerContributor {
  @Nullable
  @Override
  public Info getInfo(PsiElement psiElement) {
    if (psiElement instanceof PsiKeyword) {
      PsiElement parent = psiElement.getParent();
      if (parent instanceof PsiClass) {
        PsiClass psiClass = (PsiClass) parent;
        for (PsiMethod method : psiClass.getAllMethods()) {
          if (TestConfigurationUtil.isTestMethod(method)) {
            return createInfo(psiElement);
          }
        }
      }
    }
    if (psiElement instanceof PsiTypeParameterList) {
      PsiElement parent = psiElement.getParent();
      if (parent instanceof PsiMethod && TestConfigurationUtil.isTestMethod((PsiMethod) parent)) {
        return createInfo(psiElement);
      }
    }
    return null;
  }

  private Info createInfo(PsiElement psiElement) {
    PsiElement parent = psiElement.getParent();
    String name = "";
    if (parent instanceof PsiMethod) {
      name = ((PsiMethod) parent).getName();
    } else if (parent instanceof PsiClass) {
      name = ((PsiClass) parent).getName();
    } else {
      return null;
    }
    return new Info(
        IconLoader.getIcon("/icons/buck_icon.png"),
        psiElement1 -> "Run Test(s)",
        new RunSelectedTestAction(
            "Run " + name + " with Buck",
            "Run " + name + " with Buck",
            AllIcons.RunConfigurations.TestState.Run,
            false,
            parent),
        new RunSelectedTestAction(
            "Debug " + name + " with Buck",
            "Debug " + name + " with Buck",
            IconLoader.getIcon("/icons/actions/Debug.png"),
            true,
            parent));
  }
}
