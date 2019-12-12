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

package com.facebook.buck.features.python;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;

/**
 * Test runner for Python for the new TestX protocol. Points to a target that represents the runner
 * itself.
 */
public class PythonTestRunner extends NoopBuildRule {

  private final SourcePath src;
  private final String mainModule;

  public PythonTestRunner(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePath src,
      String mainModule) {
    super(buildTarget, projectFilesystem);
    this.src = src;
    this.mainModule = mainModule;
  }

  public SourcePath getSrc() {
    return src;
  }

  public String getMainModule() {
    return mainModule;
  }
}
