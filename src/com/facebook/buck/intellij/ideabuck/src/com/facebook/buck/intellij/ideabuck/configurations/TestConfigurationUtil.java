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

import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckQueryCommandHandler;
import com.facebook.buck.intellij.ideabuck.file.BuckFileUtil;
import com.google.common.base.Function;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiMethod;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class TestConfigurationUtil {

  private static final String[] TEST_ANNOTATIONS = {
    "org.junit.Test", "org.testng.annotations.Test"
  };

  /**
   * Create handler that fills out test configuration using buck query on current file.
   *
   * @param testConfiguration the {@link TestConfiguration} to fill out.
   * @param name a {@link String} representing the name of the configuration.
   * @param testSelectors a {@link String} representing optional testSelectors for filtering.
   * @param project {@link Project} then intellij project corresponding the the file or module under
   *     test.
   * @param containingFile {@link VirtualFile} the file that containing the impacted tests.
   * @param buckFile {@link VirtualFile} the file representing the buck File.
   * @param testConfigurationProcessor a {@link Function<TestConfiguration, null>} representing a
   *     callback to run after the test configuration is filled out.
   * @return a {@link BuckCommandHandler} that handles the buck query command used to fill out the
   *     test configuration.
   */
  public static BuckCommandHandler getTestConfigurationDataHandler(
      @Nonnull TestConfiguration testConfiguration,
      @Nonnull String name,
      final Optional<String> testSelectors,
      final Project project,
      VirtualFile containingFile,
      VirtualFile buckFile,
      @Nullable Function<TestConfiguration, Void> testConfigurationProcessor) {
    BuckCommandHandler handler =
        new BuckQueryCommandHandler(
            project,
            buckFile.getParent(),
            BuckCommand.QUERY,
            new Function<List<String>, Void>() {
              @Nullable
              @Override
              public Void apply(@Nullable List<String> strings) {
                if (!(strings == null
                    || strings.isEmpty()
                    || strings.get(0) == null
                    || strings.get(0).isEmpty())) {
                  testConfiguration.setName(name);
                  testConfiguration.data.target = strings.get(0);
                  testConfiguration.data.testSelectors = testSelectors.orElse("");
                }
                if (testConfigurationProcessor != null) {
                  testConfigurationProcessor.apply(testConfiguration);
                }
                return null;
              }
            });
    handler.command().addParameter("kind('.*_test', owner(" + containingFile.getPath() + "))");
    return handler;
  }

  /**
   * Check that a method is annotated @Test.
   *
   * @param method a {@link PsiMethod} to check.
   * @return {@code true} if the method has the junit Test annotation. {@code false} otherwise.
   */
  public static boolean isTestMethod(PsiMethod method) {
    if (method.getContext() == null) {
      return false;
    }
    if (method.getContext().getContainingFile() == null) {
      return false;
    }
    VirtualFile buckFile =
        BuckFileUtil.getBuckFile(method.getContext().getContainingFile().getVirtualFile());
    if (buckFile == null) {
      return false;
    }
    PsiAnnotation[] annotations = method.getModifierList().getAnnotations();
    for (PsiAnnotation annotation : annotations) {
      for (String testAnnotation : TEST_ANNOTATIONS) {
        if (testAnnotation.equals(annotation.getQualifiedName())) {
          return true;
        }
      }
    }
    return false;
  }
}
