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

package com.facebook.buck.cli.parameter_extractors;

import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;

/**
 * 'Simple fields' from the {@code com.facebook.buck.cli.ProjectCommand} that the {@code
 * com.facebook.buck.ide.intellij.projectview.ProjectView} needs
 */
public interface ProjectViewParameters extends ProjectGeneratorParameters {

  boolean hasViewPath();

  @Nullable
  String getViewPath();

  default boolean isValidViewPath() {
    if (!hasViewPath()) {
      return false;
    }

    Path view = Paths.get(getViewPath()).toAbsolutePath().normalize();

    return !view.startsWith(getPath());
  }
}
