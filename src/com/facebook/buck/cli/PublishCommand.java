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

import static com.facebook.buck.jvm.core.JavaLibrary.MAVEN_JAR;
import static com.facebook.buck.jvm.core.JavaLibrary.SRC_JAR;
import static com.facebook.buck.jvm.java.Javadoc.DOC_JAR;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.jvm.java.MavenPublishable;
import com.facebook.buck.maven.Publisher;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetSpec;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.deployment.DeployResult;
import org.eclipse.aether.deployment.DeploymentException;
import org.kohsuke.args4j.Option;

public class PublishCommand extends BuildCommand {
  public static final String REMOTE_REPO_LONG_ARG = "--remote-repo";
  public static final String REMOTE_REPO_SHORT_ARG = "-r";
  public static final String INCLUDE_SOURCE_LONG_ARG = "--include-source";
  public static final String INCLUDE_SOURCE_SHORT_ARG = "-s";
  public static final String INCLUDE_DOCS_LONG_ARG = "--include-docs";
  public static final String INCLUDE_DOCS_SHORT_ARG = "-w";
  public static final String TO_MAVEN_CENTRAL_LONG_ARG = "--to-maven-central";
  public static final String DRY_RUN_LONG_ARG = "--dry-run";

  @Option(
    name = REMOTE_REPO_LONG_ARG,
    aliases = REMOTE_REPO_SHORT_ARG,
    usage = "A url of the remote repository to publish artifact(s) to"
  )
  @Nullable
  private URL remoteRepo = null;

  @Option(
    name = TO_MAVEN_CENTRAL_LONG_ARG,
    usage = "Same as \"" + REMOTE_REPO_LONG_ARG + " " + Publisher.MAVEN_CENTRAL_URL + "\""
  )
  private boolean toMavenCentral = false;

  @Option(
    name = INCLUDE_SOURCE_LONG_ARG,
    aliases = INCLUDE_SOURCE_SHORT_ARG,
    usage = "Publish source code as well"
  )
  private boolean includeSource = false;

  @Option(
    name = INCLUDE_DOCS_LONG_ARG,
    aliases = INCLUDE_DOCS_SHORT_ARG,
    usage = "Publish docs as well"
  )
  private boolean includeDocs = false;

  @Option(name = DRY_RUN_LONG_ARG, usage = "Just print the artifacts to be published")
  private boolean dryRun = false;

  @Option(
    name = "--username",
    aliases = "-u",
    usage = "User name to use to authenticate with the server"
  )
  @Nullable
  private String username = null;

  @Option(
    name = "--password",
    aliases = "-p",
    usage = "Password to use to authenticate with the server"
  )
  @Nullable
  private String password = null;

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {

    // Input validation
    if (remoteRepo == null && !toMavenCentral) {
      throw new CommandLineException(
          "please specify a remote repository to publish to.\n"
              + "Use "
              + REMOTE_REPO_LONG_ARG
              + " <URL> or "
              + TO_MAVEN_CENTRAL_LONG_ARG);
    }

    // Build the specified target(s).
    ExitCode exitCode = super.runWithoutHelp(params);
    if (exitCode != ExitCode.SUCCESS) {
      return exitCode;
    }

    // Publish starting with the given targets.
    return publishTargets(getBuildTargets(), params) ? ExitCode.SUCCESS : ExitCode.RUN_ERROR;
  }

  private boolean publishTargets(
      ImmutableList<BuildTarget> buildTargets, CommandRunnerParams params) {
    ImmutableSet.Builder<MavenPublishable> publishables = ImmutableSet.builder();
    boolean success = true;
    for (BuildTarget buildTarget : buildTargets) {
      BuildRule buildRule = getBuild().getRuleResolver().requireRule(buildTarget);
      Preconditions.checkNotNull(buildRule);

      if (!(buildRule instanceof MavenPublishable)) {
        params
            .getBuckEventBus()
            .post(
                ConsoleEvent.severe(
                    "Cannot publish rule of type %s", buildRule.getClass().getName()));
        success &= false;
        continue;
      }

      MavenPublishable publishable = (MavenPublishable) buildRule;
      if (!publishable.getMavenCoords().isPresent()) {
        params
            .getBuckEventBus()
            .post(
                ConsoleEvent.severe(
                    "No maven coordinates specified for %s",
                    buildTarget.getUnflavoredBuildTarget().getFullyQualifiedName()));
        success &= false;
        continue;
      }
      publishables.add(publishable);
    }

    Publisher publisher =
        new Publisher(
            params.getCell().getFilesystem(),
            Optional.ofNullable(remoteRepo),
            Optional.ofNullable(username),
            Optional.ofNullable(password),
            dryRun);

    try {
      ImmutableSet<DeployResult> deployResults =
          publisher.publish(
              DefaultSourcePathResolver.from(
                  new SourcePathRuleFinder(getBuild().getRuleResolver())),
              publishables.build());
      for (DeployResult deployResult : deployResults) {
        printArtifactsInformation(params, deployResult);
      }
    } catch (DeploymentException e) {
      params.getConsole().printBuildFailureWithoutStacktraceDontUnwrap(e);
      return false;
    }
    return success;
  }

  private static void printArtifactsInformation(
      CommandRunnerParams params, DeployResult deployResult) {
    params
        .getConsole()
        .getStdOut()
        .println(
            "\nPublished artifacts:\n"
                + Joiner.on('\n')
                    .join(
                        FluentIterable.from(deployResult.getArtifacts())
                            .transform(PublishCommand::artifactToString)));
    params.getConsole().getStdOut().println("\nDeployRequest:\n" + deployResult.getRequest());
  }

  private static String artifactToString(Artifact artifact) {
    return artifact.toString() + " < " + artifact.getFile();
  }

  @Override
  public ImmutableList<TargetNodeSpec> parseArgumentsAsTargetNodeSpecs(
      BuckConfig config, Iterable<String> targetsAsArgs) {
    ImmutableList<TargetNodeSpec> specs =
        super.parseArgumentsAsTargetNodeSpecs(config, targetsAsArgs);

    Map<BuildTarget, TargetNodeSpec> uniqueSpecs = new HashMap<>();
    for (TargetNodeSpec spec : specs) {
      if (!(spec instanceof BuildTargetSpec)) {
        throw new IllegalArgumentException(
            "Need to specify build targets explicitly when publishing. " + "Cannot modify " + spec);
      }

      BuildTargetSpec targetSpec = (BuildTargetSpec) spec;
      Preconditions.checkNotNull(targetSpec.getBuildTarget());

      BuildTarget mavenTarget = targetSpec.getBuildTarget().withFlavors(MAVEN_JAR);
      uniqueSpecs.put(mavenTarget, targetSpec.withBuildTarget(mavenTarget));

      if (includeSource) {
        BuildTarget sourceTarget = targetSpec.getBuildTarget().withFlavors(MAVEN_JAR, SRC_JAR);
        uniqueSpecs.put(sourceTarget, targetSpec.withBuildTarget(sourceTarget));
      }

      if (includeDocs) {
        BuildTarget docsTarget = targetSpec.getBuildTarget().withFlavors(MAVEN_JAR, DOC_JAR);
        uniqueSpecs.put(docsTarget, targetSpec.withBuildTarget(docsTarget));
      }
    }

    return ImmutableList.copyOf(uniqueSpecs.values());
  }

  @Override
  public String getShortDescription() {
    return "builds and publishes a library to a central repository";
  }
}
