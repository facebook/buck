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
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckQueryCommandHandler;
import com.google.common.base.Function;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.RunConfigurationProducer;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

public class TestRunConfigurationProducer extends RunConfigurationProducer<TestConfiguration> {
  protected TestRunConfigurationProducer() {
    super(TestConfigurationType.getInstance());
  }

  @Override
  protected boolean setupConfigurationFromContext(
      TestConfiguration config,
      ConfigurationContext context,
      Ref<PsiElement> ref) {

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

    // Look for the first java_test that contains this source file. If no java_test contains this
    // source file, then return false.
    BuckBuildManager buildManager = BuckBuildManager.getInstance(context.getProject());
    final CompletableFuture<String> target = new CompletableFuture<>();
    BuckCommandHandler handler =
        new BuckQueryCommandHandler(
            context.getProject(),
            context.getProject().getBaseDir(),
            BuckCommand.QUERY,
            new Function<List<String>, Void>() {
              @Nullable
              @Override
              public Void apply(@Nullable List<String> targets) {
                if (targets != null && !targets.isEmpty()) {
                  target.complete(targets.get(0));
                }
                return null;
              }
            }
        );
    String queryString = "kind('java_test', owner('" + file.getVirtualFile().getPath() + "'))";
    handler.command().addParameter(queryString);
    buildManager.runInCurrentThread(handler, null, true, null);
    config.data.target = target.getNow(null);
    if (config.data.target == null) {
      return false;
    }

    config.data.testSelectors = psiClass.getQualifiedName();
    config.setName(psiClass.getName());
    return true;
  }

  @Override
  public boolean isConfigurationFromContext(
      TestConfiguration config,
      ConfigurationContext context) {
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
