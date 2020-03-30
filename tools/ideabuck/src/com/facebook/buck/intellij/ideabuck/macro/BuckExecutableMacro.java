/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.intellij.ideabuck.macro;

import com.facebook.buck.intellij.ideabuck.config.BuckExecutableSettingsProvider;
import com.intellij.ide.macro.Macro;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/** Macro that expands to the full path to the buck executable. */
public class BuckExecutableMacro extends Macro {

  @Override
  public String getName() {
    return "BuckExecutable";
  }

  @Override
  public String getDescription() {
    return "Path to the buck executable for the active project.";
  }

  @Nullable
  @Override
  public String expand(DataContext dataContext) throws ExecutionCancelledException {
    return Optional.ofNullable(CommonDataKeys.PROJECT.getData(dataContext))
        .filter(project -> !project.isDefault())
        .map(BuckExecutableSettingsProvider::getInstance)
        .map(BuckExecutableSettingsProvider::resolveBuckExecutable)
        .orElse(null);
  }
}
