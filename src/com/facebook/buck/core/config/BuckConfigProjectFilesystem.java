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

package com.facebook.buck.core.config;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;

/**
 * {@link BuckConfig} depends on filesystem unfortunately. But we need buckconfig to initialize
 * filesystem.
 *
 * <p>This class exists for delay filesystem initialization for {@link BuckConfig}.
 */
public class BuckConfigProjectFilesystem {

  @Nullable private ProjectFilesystem projectFilesystem;

  public BuckConfigProjectFilesystem(ProjectFilesystem projectFilesystem) {
    Preconditions.checkArgument(projectFilesystem != null);
    this.projectFilesystem = projectFilesystem;
  }

  public BuckConfigProjectFilesystem() {
    projectFilesystem = null;
  }

  @Nullable
  public ProjectFilesystem getProjectFilesystem() {
    Preconditions.checkState(projectFilesystem != null, "projectFilesystem was not initialized");
    return projectFilesystem;
  }

  /** Call once to initialize filesystem. */
  public void initFilesystem(ProjectFilesystem projectFilesystem) {
    Preconditions.checkState(
        this.projectFilesystem == null, "projectFilesystem already initialized");
    this.projectFilesystem = projectFilesystem;
  }
}
