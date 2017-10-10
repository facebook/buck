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

package com.facebook.buck.intellij.ideabuck.proj;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import java.util.Arrays;
import org.jetbrains.android.facet.AndroidFacet;

public final class BuckProjectInfo {
  private BuckProjectInfo() {
    throw new AssertionError("No instances");
  }

  public static boolean isBuckProject(Project project) {
    return BuckBuildManager.getInstance(project).isBuckProject(project);
  }

  public static boolean hasAndroidModule(Project project) {
    return Arrays.stream(ModuleManager.getInstance(project).getModules())
        .anyMatch(BuckProjectInfo::isAndroidModule);
  }

  public static boolean isAndroidModule(Module module) {
    return AndroidFacet.getInstance(module) != null;
  }
}
