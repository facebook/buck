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

package com.facebook.buck.intellij.ideabuck.tool;

import com.facebook.buck.intellij.ideabuck.api.BuckCellManager;
import com.facebook.buck.intellij.ideabuck.config.BuckExecutableDetector;
import com.facebook.buck.intellij.ideabuck.config.BuckExecutableSettingsProvider;
import com.intellij.openapi.project.Project;
import com.intellij.tools.Tool;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class BuckTool extends Tool {

  private final String mName;
  private final boolean mUseConsole;

  public BuckTool(String name, boolean useConsole, Project project) {
    mName = name;
    mUseConsole = useConsole;
    // Using copyFrom() as a hacky workaround for the visibility issue
    // that Tool doesn't expose the setters setName() or setUseConsole().
    this.copyFrom(this);
    setProgram(
        getBuckExecutable(project)
            .map(Path::toString)
            .orElse(BuckExecutableDetector.DEFAULT_BUCK_NAME));
    getDefaultCellPath(project).ifPresent(path -> setWorkingDirectory(path.toString()));
  }

  @Override
  public String getName() {
    return mName;
  }

  @Override
  public boolean isUseConsole() {
    return mUseConsole;
  }

  private Optional<Path> getBuckExecutable(Project project) {
    return Optional.of(project)
        .map(BuckExecutableSettingsProvider::getInstance)
        .map(BuckExecutableSettingsProvider::resolveBuckExecutable)
        .map(Paths::get);
  }

  private Optional<Path> getDefaultCellPath(Project project) {
    return Optional.of(project)
        .map(BuckCellManager::getInstance)
        .flatMap(BuckCellManager::getDefaultCell)
        .map(BuckCellManager.Cell::getRootPath);
  }
}
