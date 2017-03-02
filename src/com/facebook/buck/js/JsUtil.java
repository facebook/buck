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

package com.facebook.buck.js;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.WorkerJobParams;
import com.facebook.buck.shell.WorkerProcessPoolFactory;
import com.facebook.buck.shell.WorkerShellStep;
import com.facebook.buck.shell.WorkerTool;

import java.util.Optional;

public class JsUtil {
  private JsUtil() {}

  static WorkerShellStep workerShellStep(
      WorkerTool worker,
      String jobArgs,
      BuildTarget buildTarget,
      SourcePathResolver sourcePathResolver,
      ProjectFilesystem projectFilesystem) {
    final Tool tool = worker.getTool();
    final WorkerJobParams params = WorkerJobParams.of(
        worker.getTempDir(),
        tool.getCommandPrefix(sourcePathResolver),
        worker.getArgs(sourcePathResolver),
        tool.getEnvironment(sourcePathResolver),
        jobArgs,
        worker.getMaxWorkers(),
        worker.isPersistent()
            ? Optional.of(buildTarget.getCellPath().toString() + buildTarget.toString())
            : Optional.empty(),
        Optional.of(worker.getInstanceKey()));
    return new WorkerShellStep(
        Optional.of(params),
        Optional.empty(),
        Optional.empty(),
        new WorkerProcessPoolFactory(projectFilesystem));
  }
}
