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

package com.facebook.buck.intellij.ideabuck.configurations;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.build.BuckCommandHandler;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.RunConfigurationProducer;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class TestRunConfigurationProducer extends RunConfigurationProducer<TestConfiguration> {
  protected TestRunConfigurationProducer() {
    super(TestConfigurationType.getInstance());
  }

  @Override
  protected boolean setupConfigurationFromContext(
      TestConfiguration config, ConfigurationContext context, Ref<PsiElement> ref) {

    PsiElement location = context.getPsiLocation();
    if (location == null) {
      return false;
    }
    PsiFile file = location.getContainingFile();
    if (file == null) {
      return false;
    }
    PsiClass psiClass = PsiTreeUtil.getChildOfType(file.getOriginalElement(), PsiClass.class);
    if (psiClass == null) {
      return false;
    }

    PsiMethod method = PsiTreeUtil.getParentOfType(location, PsiMethod.class);
    String name;
    Optional<String> testSelector;
    if (method != null && TestConfigurationUtil.isTestMethod(method)) {
      testSelector = Optional.of(psiClass.getQualifiedName() + "#" + method.getName());
      name = method.getName();
    } else {
      testSelector = Optional.ofNullable(psiClass.getQualifiedName());
      name = Optional.ofNullable(psiClass.getName()).orElse("");
    }

    BuckBuildManager buildManager = BuckBuildManager.getInstance(context.getProject());
    final CompletableFuture<Boolean> success = new CompletableFuture<>();
    BuckCommandHandler handler =
        TestConfigurationUtil.getTestConfigurationDataHandler(
            config,
            name,
            testSelector,
            context.getProject(),
            file.getVirtualFile(),
            context.getProject().getProjectFile(),
            configuration -> {
              success.complete(configuration.data.target != null);
              return null;
            });
    buildManager.runInCurrentThread(handler, null, true, null);
    return success.getNow(false);
  }

  @Override
  public boolean isConfigurationFromContext(
      TestConfiguration config, ConfigurationContext context) {
    PsiElement location = context.getPsiLocation();
    if (location == null) {
      return false;
    }
    PsiFile file = location.getContainingFile();
    if (file == null) {
      return false;
    }
    PsiClass psiClass = PsiTreeUtil.getChildOfType(file.getOriginalElement(), PsiClass.class);
    return psiClass != null && config.data.testSelectors.equals(psiClass.getQualifiedName());
  }
}
