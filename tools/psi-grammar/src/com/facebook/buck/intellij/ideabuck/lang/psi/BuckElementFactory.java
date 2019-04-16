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
package com.facebook.buck.intellij.ideabuck.lang.psi;

import com.facebook.buck.intellij.ideabuck.lang.BuckFile;
import com.facebook.buck.intellij.ideabuck.lang.BuckLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;

/** Utility methods for creating new {@link BuckElementType} objects from arbitrary input. */
public class BuckElementFactory {

  /**
   * Creates a Buck element by extracting the first element of the given type from a BuckFile with
   * the given text.
   */
  public static <T extends PsiElement> T createElement(
      Project project, String fileText, Class<T> elementClass) {
    BuckFile file = createFile(project, fileText);
    return PsiTreeUtil.findChildOfType(file, elementClass);
  }

  /** Create a {@link BuckFile} seeded with the given text. */
  public static BuckFile createFile(Project project, String text) {
    PsiFileFactory psiFileFactory = PsiFileFactory.getInstance(project);
    return (BuckFile) psiFileFactory.createFileFromText(BuckLanguage.INSTANCE, text);
  }
}
