/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.go;

import com.facebook.buck.cli.CommandRunnerParams;
import com.facebook.buck.cli.CommandThreadManager;
import com.facebook.buck.cli.ProjectSubCommand;
import com.facebook.buck.cli.parameter_extractors.ProjectGeneratorParameters;
import com.facebook.buck.util.ExitCode;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.List;
import org.pf4j.Extension;

@Extension
public class GoProjectSubCommand extends ProjectSubCommand {

  @Override
  public ExitCode run(
      CommandRunnerParams params,
      CommandThreadManager threadManager,
      ProjectGeneratorParameters projectGeneratorParameters,
      List<String> projectCommandArguments)
      throws IOException, InterruptedException {
    ListeningExecutorService executor = threadManager.getListeningExecutorService();

    GoProjectCommandHelper projectCommandHelper =
        new GoProjectCommandHelper(
            params,
            executor,
            projectGeneratorParameters.getEnableParserProfiling(),
            projectGeneratorParameters.getArgsParser(),
            projectGeneratorParameters);
    return projectCommandHelper.parseTargetsAndRunProjectGenerator(projectCommandArguments);
  }

  @Override
  public String getOptionValue() {
    return "gobuild";
  }

  @Override
  public String getShortDescription() {
    return "project generation for gobuild";
  }
}
