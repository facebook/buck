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
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

public final class BuckBuildTargetUtil {

  private BuckBuildTargetUtil() {
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
}
