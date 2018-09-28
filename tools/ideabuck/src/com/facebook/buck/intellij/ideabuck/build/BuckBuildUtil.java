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

package com.facebook.buck.intellij.ideabuck.build;

import com.facebook.buck.intellij.ideabuck.file.BuckFileUtil;
import com.facebook.buck.intellij.ideabuck.lang.BuckFile;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgumentList;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionCall;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadTargetArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPsiUtils;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSingleExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class BuckBuildUtil {

  public static final String BUCK_CONFIG_FILE = ".buckconfig";
  public static final String BUCK_FILE_NAME = BuckFileUtil.getBuildFileName();

  // TODO(#7908675): Use Buck's classes and get rid of these.
  public static final String PROJECT_CONFIG_RULE_NAME = "project_config";
  public static final String SRC_TARGET_PROPERTY_NAME = "src_target";

  private BuckBuildUtil() {}

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
   * @param target The build target in absolute (<code>//apps/myapp:app</code>}) or relative (<code>
   *     :app</code>) pattern.
   * @return The name of the target, for example <code>app</code>.
   */
  public static String extractTargetName(String target) {
    return target.substring(target.lastIndexOf(":") + 1);
  }

  /**
   * @param loadTarget The <code>load</code> function target argument like <code>//pkg:ext.bzl
   *     </code>.
   * @return The file with extension pointed by the provided target argument.
   */
  @Nullable
  public static VirtualFile resolveExtensionFile(BuckLoadTargetArgument loadTarget) {
    String target = loadTarget.getText();
    target = target.substring(1, target.length() - 1); // strip quotes
    if (!BuckBuildUtil.isValidAbsoluteTarget(target)) {
      return null;
    }
    String packagePath = BuckBuildUtil.extractAbsoluteTarget(target);
    String fileName = BuckBuildUtil.extractTargetName(target);
    Project project = loadTarget.getProject();
    @Nullable
    VirtualFile packageDirectory = project.getBaseDir().findFileByRelativePath(packagePath);
    return packageDirectory != null ? packageDirectory.findChild(fileName) : null;
  }

  /**
   * Return the virtual file of the BUCK file of the given target. TODO(#7908675): Use Buck's
   * BuildTargetFactory and deprecate this class.
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
   * Get the buck target from a buck file. TODO(#7908675): We should use Buck's own classes for it.
   */
  public static String extractBuckTarget(Project project, VirtualFile file) {
    BuckFile buckFile = (BuckFile) PsiManager.getInstance(project).findFile(file);
    if (buckFile == null) {
      return null;
    }

    PsiElement[] children = buckFile.getChildren();
    for (PsiElement child : children) {
      if (child.getNode().getElementType() == BuckTypes.STATEMENT) {
        BuckFunctionCall functionCall = PsiTreeUtil.findChildOfType(child, BuckFunctionCall.class);
        // Find rule "project_config"
        if (functionCall != null
            && functionCall.getFunctionName().getText().equals(PROJECT_CONFIG_RULE_NAME)) {
          return getPropertyValue(
              functionCall.getFunctionCallSuffix().getArgumentList(), SRC_TARGET_PROPERTY_NAME);
        }
      }
    }
    return null;
  }

  /**
   * Get the value of a property in a specific buck rule body. TODO(#7908675): We should use Buck's
   * own classes for it.
   */
  public static String getPropertyValue(BuckArgumentList argumentList, String name) {
    if (argumentList == null) {
      return null;
    }
    List<BuckArgument> arguments = argumentList.getArgumentList();
    for (BuckArgument arg : arguments) {
      PsiElement lvalue = arg.getPropertyLvalue();
      if (lvalue != null) {
        PsiElement propertyName = lvalue.getFirstChild();
        if (propertyName != null && propertyName.getText().equals(name)) {
          BuckSingleExpression expression = arg.getSingleExpression();
          return BuckPsiUtils.getStringValueFromExpression(expression);
        }
      }
    }
    return null;
  }
}
