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

package com.facebook.buck.cli;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.DefaultJavaLibrary;
import com.facebook.buck.maven.Publisher;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import org.eclipse.aether.deployment.DeploymentException;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

import javax.annotation.Nullable;

public class PublishCommand extends BuildCommand {
  public static final String REMOTE_REPO_LONG_ARG = "--remote-repo";
  public static final String REMOTE_REPO_SHORT_ARG = "-r";
  public static final String TO_MAVEN_CENTRAL_LONG_ARG = "--to-maven-central";

  @Option(
      name = REMOTE_REPO_LONG_ARG,
      aliases = REMOTE_REPO_SHORT_ARG,
      usage = "A url of the remote repository to publish artifact(s) to")
  @Nullable
  private URL remoteRepo = null;

  @Option(
      name = TO_MAVEN_CENTRAL_LONG_ARG,
      usage = "Same as \"" + REMOTE_REPO_LONG_ARG + " " + Publisher.MAVEN_CENTRAL_URL + "\"")
  private boolean toMavenCentral = false;

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {

    // Input validation
    if (remoteRepo == null && !toMavenCentral) {
      printError(params, "Please specify a remote repository to publish to.\n" +
              "Use " + REMOTE_REPO_LONG_ARG + " <URL> or " + TO_MAVEN_CENTRAL_LONG_ARG);
      return 1;
    }

    // Build the specified target(s).
    int exitCode = super.runWithoutHelp(params);
    if (exitCode != 0) {
      return exitCode;
    }

    // Publish given targets
    boolean success = true;
    for (BuildTarget buildTarget : getBuildTargets()) {
      success &= publishTarget(buildTarget, params);
    }

    return success ? 0 : 1;
  }

  /**
   * @return whether successful
   */
  private boolean publishTarget(BuildTarget buildTarget, CommandRunnerParams params) {
    BuildRule buildRule = Preconditions.checkNotNull(
        getBuild().getActionGraph().findBuildRuleByTarget(buildTarget));

    if (!(buildRule instanceof DefaultJavaLibrary)) {
      printError(
          params,
          "Cannot retrieve maven coordinates for rule of type " + buildRule.getClass().getName());
      return false;
    }

    DefaultJavaLibrary javaLibrary = (DefaultJavaLibrary) buildRule;
    Optional<String> mavenCoords = javaLibrary.getMavenCoords();
    if (!mavenCoords.isPresent()) {
      printError(params, "No maven coordinates specified for published rule " + buildRule);
      return false;
    }

    Path relativePathToOutput = buildRule.getPathToOutput();
    if (relativePathToOutput == null) {
      printError(params, "No path to output present in " + buildRule);
      return false;
    }

    ProjectFilesystem projectFilesystem = params.getRepository().getFilesystem();
    Path pathToOutput = projectFilesystem.resolve(relativePathToOutput);
    Publisher publisher = new Publisher(projectFilesystem, Optional.fromNullable(remoteRepo));

    try {
      publisher.publish(mavenCoords.get(), pathToOutput.toFile());
    } catch (DeploymentException e) {
      params.getConsole().printBuildFailureWithoutStacktraceDontUnwrap(e);
      return false;
    }
    return true;
  }

  private void printError(CommandRunnerParams params, String errorMessage) {
    params.getConsole().printErrorText(errorMessage);
  }


  @Override
  public String getShortDescription() {
    return "builds and publishes a library to a central repository";
  }
}
