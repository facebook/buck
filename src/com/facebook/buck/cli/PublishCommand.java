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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.file.OutputFilePublisher;
import com.facebook.buck.jvm.java.MavenPublishable;
import com.facebook.buck.maven.Publisher;
import com.facebook.buck.parser.BuildTargetSpec;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.ExitCode;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import okhttp3.Credentials;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.deployment.DeployResult;
import org.eclipse.aether.deployment.DeploymentException;
import org.kohsuke.args4j.Option;

public class PublishCommand extends BuildCommand {
  public static final String REMOTE_REPO_LONG_ARG = "--remote-repo";
  public static final String REMOTE_REPO_SHORT_ARG = "-r";
  public static final String TO_MAVEN_LONG_ARG = "--to-maven";
  public static final String INCLUDE_SOURCE_LONG_ARG = "--include-source";
  public static final String INCLUDE_SOURCE_SHORT_ARG = "-s";
  public static final String INCLUDE_DOCS_LONG_ARG = "--include-docs";
  public static final String INCLUDE_DOCS_SHORT_ARG = "-w";
  public static final String DRY_RUN_LONG_ARG = "--dry-run";

  private static final String PUBLISH_GEN_PATH = "publish";

  @Option(
      name = REMOTE_REPO_LONG_ARG,
      aliases = REMOTE_REPO_SHORT_ARG,
      usage = "A url of the remote repository to publish artifact(s) to")
  @Nullable
  private URL remoteRepo = null;

  @Option(
      name = TO_MAVEN_LONG_ARG,
      usage =
          "Indicate that the remote repository is a maven repository and automatically set "
              + REMOTE_REPO_LONG_ARG
              + " to "
              + Publisher.MAVEN_CENTRAL_URL
              + " unless it is provided otherwise.")
  private boolean toMaven = false;

  @Option(
      name = INCLUDE_SOURCE_LONG_ARG,
      aliases = INCLUDE_SOURCE_SHORT_ARG,
      usage = "Publish source code as well. Only valid for Maven repositories")
  private boolean includeSource = false;

  @Option(
      name = INCLUDE_DOCS_LONG_ARG,
      aliases = INCLUDE_DOCS_SHORT_ARG,
      usage = "Publish docs as well. Only valid for Maven repositories")
  private boolean includeDocs = false;

  @Option(name = DRY_RUN_LONG_ARG, usage = "Just print the artifacts to be published")
  private boolean dryRun = false;

  @Option(
      name = "--username",
      aliases = "-u",
      usage = "User name to use to authenticate with the server")
  @Nullable
  private String username = null;

  @Option(
      name = "--password",
      aliases = "-p",
      usage = "Password to use to authenticate with the server")
  @Nullable
  private String password = null;

  @Override
  public ExitCode runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException {

    // Input validation
    if (!toMaven && (includeDocs || includeSource)) {
      throw new CommandLineException(
          INCLUDE_DOCS_LONG_ARG
              + " and "
              + INCLUDE_SOURCE_LONG_ARG
              + " are only available when publishing to a Maven repository");
    }

    if (remoteRepo == null) {
      if (toMaven) {
        remoteRepo = Publisher.MAVEN_CENTRAL;
      } else {
        throw new CommandLineException(
            "please specify a remote repository to publish to.\n"
                + "Use "
                + REMOTE_REPO_LONG_ARG
                + " <URL> or "
                + TO_MAVEN_LONG_ARG);
      }
    }

    // Build the specified target(s).

    assertArguments(params);

    BuildRunResult buildRunResult;
    try (CommandThreadManager pool =
        new CommandThreadManager("Publish", getConcurrencyLimit(params.getBuckConfig()))) {
      buildRunResult = super.run(params, pool, ImmutableSet.of());
    }

    ExitCode exitCode = buildRunResult.getExitCode();
    if (exitCode != ExitCode.SUCCESS) {
      return exitCode;
    }

    // Publish starting with the given targets.
    boolean success;
    if (toMaven) {
      success = publishMavenTargets(buildRunResult.getBuildTargets(), params);
    } else {
      success = publishFileTargets(buildRunResult.getBuildTargets(), params);
    }
    return success ? ExitCode.SUCCESS : ExitCode.RUN_ERROR;
  }

  private boolean publishFileTargets(
      ImmutableSet<BuildTarget> buildTargets, CommandRunnerParams params) {
    Deque<CompletableFuture<Integer>> futures = new ArrayDeque<>();
    boolean success = true;
    ActionGraphBuilder graphBuilder = getBuild().getGraphBuilder();
    DefaultSourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    BuckEventBus buckEventBus = params.getBuckEventBus();
    for (BuildTarget target : buildTargets) {
      BuildRule buildRule = graphBuilder.requireRule(target);
      Preconditions.checkNotNull(buildRule);
      futures.add(
          CompletableFuture.supplyAsync(
              new OutputFilePublisher(
                  buildRule,
                  pathResolver,
                  buckEventBus,
                  remoteRepo,
                  Credentials.basic(username, password)),
              getExecutionContext().getExecutors().get(ExecutorPool.NETWORK)));
    }
    while (!futures.isEmpty()) {
      CompletableFuture<Integer> future = futures.pop();
      try {
        if (future.isDone()) {
          int resposeCode = future.get();
          if (resposeCode >= 300 || resposeCode < 200) {
            success = false;
            if (resposeCode != -1) {
              buckEventBus.post(
                  ConsoleEvent.severe(
                      "Publishing to " + remoteRepo + " returned a response code " + resposeCode));
            }
          }
        } else {
          futures.push(future);
          TimeUnit.SECONDS.sleep(1);
        }
      } catch (InterruptedException | ExecutionException e) {
        buckEventBus.post(ConsoleEvent.warning(e.getMessage()));
        success = false;
      }
    }
    return success;
  }

  private boolean publishMavenTargets(
      ImmutableSet<BuildTarget> buildTargets, CommandRunnerParams params) {
    ImmutableSet.Builder<MavenPublishable> publishables = ImmutableSet.builder();
    boolean success = true;
    for (BuildTarget buildTarget : buildTargets) {
      BuildRule buildRule = getBuild().getGraphBuilder().requireRule(buildTarget);
      Preconditions.checkNotNull(buildRule);

      if (!(buildRule instanceof MavenPublishable)) {
        params
            .getBuckEventBus()
            .post(
                ConsoleEvent.severe(
                    "Cannot publish rule of type %s", buildRule.getClass().getName()));
        success = false;
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
        success = false;
        continue;
      }
      publishables.add(publishable);
    }

    Publisher publisher =
        new Publisher(
            params.getCell().getFilesystem().getBuckPaths().getTmpDir().resolve(PUBLISH_GEN_PATH),
            remoteRepo,
            Optional.ofNullable(username),
            Optional.ofNullable(password),
            dryRun);

    try {
      ImmutableSet<DeployResult> deployResults =
          publisher.publish(
              DefaultSourcePathResolver.from(
                  new SourcePathRuleFinder(getBuild().getGraphBuilder())),
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
    return artifact + " < " + artifact.getFile();
  }

  @Override
  public ImmutableList<TargetNodeSpec> parseArgumentsAsTargetNodeSpecs(
      BuckConfig config, Iterable<String> targetsAsArgs) {
    ImmutableList<TargetNodeSpec> specs =
        super.parseArgumentsAsTargetNodeSpecs(config, targetsAsArgs);

    if (toMaven) {
      Map<BuildTarget, TargetNodeSpec> uniqueSpecs = new HashMap<>();
      for (TargetNodeSpec spec : specs) {
        if (!(spec instanceof BuildTargetSpec)) {
          throw new IllegalArgumentException(
              "Need to specify build targets explicitly when publishing. "
                  + "Cannot modify "
                  + spec);
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
    } else {
      return specs;
    }
  }

  @Override
  public String getShortDescription() {
    return "builds and publishes a library to a central repository";
  }
}
