/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.intellij.plugin.build;

import com.facebook.buck.intellij.plugin.file.BuckFileUtil;
import com.facebook.buck.intellij.plugin.lang.BuckFile;
import com.facebook.buck.intellij.plugin.lang.psi.BuckExpression;
import com.facebook.buck.intellij.plugin.lang.psi.BuckPsiUtils;
import com.facebook.buck.intellij.plugin.lang.psi.BuckRuleBody;
import com.facebook.buck.intellij.plugin.lang.psi.BuckTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;

public final class BuckBuildUtil {

  public static final String BUCK_CONFIG_FILE = ".buckconfig";
  public static final String BUCK_FILE_NAME = BuckFileUtil.getBuildFileName();

  // TODO(#7908675): Use Buck's classes and get rid of these.
  public static final String PROJECT_CONFIG_RULE_NAME = "project_config";
  public static final String SRC_TARGET_PROPERTY_NAME = "src_target";

  private BuckBuildUtil() {
  }

  public static boolean isValidAbsoluteTarget(String target) {
    return target.matches("^//[\\s\\S]*:[\\s\\S]*$");
  }

  /**
   * @param target The absolute target in "//apps/myapp:app" pattern.
   * @return The absolute path of the target, for example "apps/myapp".
   */
  public static String extractAbsoluteTarget(String target) {
    return target.substring(2, target.lastIndexOf(":"));
  }

  /**
   * Return the virtual file of the BUCK file of the given target.
   * TODO(#7908675): Use Buck's BuildTargetFactory and deprecate this class.
   */
  public static VirtualFile getBuckFileFromAbsoluteTarget(Project project, String target) {
    if (!isValidAbsoluteTarget(target)) {
      return null;
    }
    VirtualFile buckDir =
        project.getBaseDir().findFileByRelativePath(extractAbsoluteTarget(target));
    return buckDir != null ? buckDir.findChild(BuckFileUtil.getBuildFileName()) : null;
  }

  /**
   * Get the buck target from a buck file.
   * TODO(#7908675): We should use Buck's own classes for it.
   */
  public static String extractBuckTarget(Project project, VirtualFile file) {
    BuckFile buckFile = (BuckFile) PsiManager.getInstance(project).findFile(file);
    if (buckFile == null) {
      return null;
    }

    PsiElement[] children = buckFile.getChildren();
    for (PsiElement child : children) {
      if (child.getNode().getElementType() == BuckTypes.RULE_BLOCK) {
        PsiElement ruleName = child.getFirstChild();
        // Find rule "project_config"
        if (ruleName != null &&
            BuckPsiUtils.testType(ruleName, BuckTypes.RULE_NAME) &&
            ruleName.getText().equals(PROJECT_CONFIG_RULE_NAME)) {
          // Find property "src_target"
          PsiElement bodyElement = BuckPsiUtils.findChildWithType(child, BuckTypes.RULE_BODY);
          return getPropertyValue((BuckRuleBody) bodyElement, SRC_TARGET_PROPERTY_NAME);
        }
      }
    }
    return null;
  }

  /**
   * Get the value of a property in a specific buck rule body.
   * TODO(#7908675): We should use Buck's own classes for it.
   */
  public static String getPropertyValue(BuckRuleBody body, String name) {
    if (body == null) {
      return null;
    }
    PsiElement[] children = body.getChildren();
    for (PsiElement child : children) {
      if (BuckPsiUtils.testType(child, BuckTypes.PROPERTY)) {
        PsiElement lvalue = child.getFirstChild();
        PsiElement propertyName = lvalue.getFirstChild();
        if (propertyName != null && propertyName.getText().equals(name)) {
          BuckExpression expression =
              (BuckExpression) BuckPsiUtils.findChildWithType(child, BuckTypes.EXPRESSION);
          return expression != null ? BuckPsiUtils.getStringValueFromExpression(expression) : null;
        }
      }
    }
    return null;
  }

  /**
   * Find the buck file from a directory.
   * TODO(#7908675): We should use Buck's own classes for it.
   */
  public static VirtualFile getBuckFileFromDirectory(VirtualFile file) {
    if (file == null) {
      return null;
    }
    VirtualFile buckFile = file.findChild(BUCK_FILE_NAME);
    while (buckFile == null && file != null) {
      buckFile = file.findChild(BUCK_FILE_NAME);
      file = file.getParent();
    }
    return buckFile;
  }
}
