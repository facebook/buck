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

import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.MavenPublishable;
import com.facebook.buck.maven.Publisher;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetSpec;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.rules.BuildRule;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.deployment.DeployResult;
import org.eclipse.aether.deployment.DeploymentException;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

import javax.annotation.Nullable;

public class PublishCommand extends BuildCommand {
  public static final String REMOTE_REPO_LONG_ARG = "--remote-repo";
  public static final String REMOTE_REPO_SHORT_ARG = "-r";
  public static final String INCLUDE_SOURCE_LONG_ARG = "--include-source";
  public static final String INCLUDE_SOURCE_SHORT_ARG = "-s";
  public static final String TO_MAVEN_CENTRAL_LONG_ARG = "--to-maven-central";
  public static final String DRY_RUN_LONG_ARG = "--dry-run";

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

  @Option(
      name = INCLUDE_SOURCE_LONG_ARG,
      aliases = INCLUDE_SOURCE_SHORT_ARG,
      usage = "Publish source code as well")
  private boolean includeSource = false;

  @Option(
      name = DRY_RUN_LONG_ARG,
      usage = "Just print the artifacts to be published")
  private boolean dryRun = false;

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

    if (!(buildRule instanceof MavenPublishable)) {
      printError(
          params,
          "Cannot retrieve maven coordinates for rule of type " + buildRule.getClass().getName());
      return false;
    }

    Publisher publisher = new Publisher(
        params.getRepository().getFilesystem(),
        Optional.fromNullable(remoteRepo),
        dryRun);

    try {
      DeployResult deployResult = publisher.publish((MavenPublishable) buildRule);
      printArtifactsInformation(params, deployResult);
    } catch (DeploymentException e) {
      params.getConsole().printBuildFailureWithoutStacktraceDontUnwrap(e);
      return false;
    }
    return true;
  }

  private static void printArtifactsInformation(
      CommandRunnerParams params,
      DeployResult deployResult) {
    params.getConsole().getStdOut().println(
        "\nPublished artifacts:\n" +
            Joiner.on('\n').join(
                FluentIterable
                    .from(deployResult.getArtifacts())
                    .transform(
                        new Function<Artifact, String>() {
                          @Override
                          public String apply(Artifact input) {
                            return atrifactToString(input);
                          }
                        })));
    params.getConsole().getStdOut().println("\nDeployRequest:\n" + deployResult.getRequest());
  }

  private static void printError(CommandRunnerParams params, String errorMessage) {
    params.getConsole().printErrorText(errorMessage);
  }

  private static String atrifactToString(Artifact artifact) {
    return artifact.toString() + " < " + artifact.getFile();
  }

  @Override
  public ImmutableList<TargetNodeSpec> parseArgumentsAsTargetNodeSpecs(
      BuckConfig config, ImmutableSet<Path> ignorePaths, Iterable<String> targetsAsArgs) {
    ImmutableList<TargetNodeSpec> specs = super.parseArgumentsAsTargetNodeSpecs(
        config,
        ignorePaths,
        targetsAsArgs);

    if (includeSource) {
      specs = ImmutableList.<TargetNodeSpec>builder()
          .addAll(specs)
          .addAll(FluentIterable
              .from(specs)
              .filter(
                  new Predicate<TargetNodeSpec>() {
                    @Override
                    public boolean apply(TargetNodeSpec input) {
                      if (!(input instanceof BuildTargetSpec)) {
                        throw new IllegalArgumentException(
                            "Targets must be explicitly defined when using " +
                                INCLUDE_SOURCE_LONG_ARG);
                      }
                      return !((BuildTargetSpec) input)
                          .getBuildTarget()
                          .getFlavors()
                          .contains(JavaLibrary.SRC_JAR);
                    }
                  })
              .transform(
                  new Function<TargetNodeSpec, BuildTargetSpec>() {
                    @Override
                    public BuildTargetSpec apply(TargetNodeSpec input) {
                      return BuildTargetSpec.of(
                          ((BuildTargetSpec) input)
                              .getBuildTarget()
                              .withFlavors(JavaLibrary.SRC_JAR),
                          input.getBuildFileSpec());
                    }
                  }))
          .build();
    }
    return specs;
  }

  @Override
  public String getShortDescription() {
    return "builds and publishes a library to a central repository";
  }
}
