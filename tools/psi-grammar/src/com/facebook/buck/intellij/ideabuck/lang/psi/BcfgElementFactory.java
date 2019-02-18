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

import com.facebook.buck.intellij.ideabuck.lang.BcfgFile;
import com.facebook.buck.intellij.ideabuck.lang.BcfgLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFileFactory;

/** Utility methods for creating new {@link BcfgElementType} objects from arbitrary input. */
public class BcfgElementFactory {

  /** Create a property with the given section name, property name, and property value. */
  public static BcfgProperty createProperty(
      Project project, String sectionName, String propertyName, String propertyValue) {
    BcfgFile file =
        createFile(
            project, "[" + sectionName + "]\n    " + propertyName + "=" + propertyValue + "\n");
    BcfgSection section = (BcfgSection) file.getFirstChild();
    return section.getPropertyList().get(0);
  }

  /** Create an empty section with the given section name. */
  public static BcfgSection createSection(Project project, String name) {
    BcfgFile file = createFile(project, "[" + name + "]");
    return (BcfgSection) file.getFirstChild();
  }

  /** Create a {@link BcfgFile} seeded with the given text. */
  public static BcfgFile createFile(Project project, String text) {
    PsiFileFactory psiFileFactory = PsiFileFactory.getInstance(project);
    return (BcfgFile) psiFileFactory.createFileFromText(BcfgLanguage.INSTANCE, text);
  }
}
