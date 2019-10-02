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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.ExportDependencies;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleTuple;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesAction;
import com.facebook.buck.step.ImmutableStepExecutionResult;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/**
 * The step that reports dependencies not used during Java compilation.
 *
 * <p>It uses class usage map produced during compilation and compares it to the outputs of the
 * dependencies specified in Java libraries. The entries from classpath not used during compilation
 * are resolved to the corresponding targets and reported back to the user either as an error (which
 * halt the build) or as a warning.
 */
@Value.Immutable(builder = false, copy = false)
@BuckStylePackageVisibleTuple
public abstract class AbstractUnusedDependenciesFinder implements Step {

  private static final Logger LOG = Logger.get(AbstractUnusedDependenciesFinder.class);

  public abstract BuildTarget getBuildTarget();

  public abstract ProjectFilesystem getProjectFilesystem();

  public abstract BuildRuleResolver getBuildRuleResolver();

  public abstract CellPathResolver getCellPathResolver();

  public abstract Path getDepFileRelativePath();

  public abstract JavaLibraryDeps getJavaLibraryDeps();

  public abstract SourcePathResolver getSourcePathResolver();

  public abstract UnusedDependenciesAction getUnusedDependenciesAction();

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    Preconditions.checkState(getUnusedDependenciesAction() != UnusedDependenciesAction.IGNORE);

    ImmutableSet<Path> usedJars = loadUsedJarPaths();
    MessageHandler messageHandler = chooseMessageHandler(context);

    findUnusedDependenciesAndProcessMessages(messageHandler, usedJars);

    logDiagnosticsIfNeeded(messageHandler, usedJars);

    Optional<String> message = messageHandler.getFinalMessage();
    if (message.isPresent()) {
      return ImmutableStepExecutionResult.builder()
          .setExitCode(StepExecutionResults.ERROR_EXIT_CODE)
          .setStderr(message)
          .build();
    } else {
      return StepExecutionResults.SUCCESS;
    }
  }

  private ImmutableSet<Path> loadUsedJarPaths() throws IOException {
    Path depFile = getProjectFilesystem().getPathForRelativePath(getDepFileRelativePath());
    if (!depFile.toFile().exists()) {
      return ImmutableSet.of();
    }

    return DefaultClassUsageFileReader.loadUsedJarsFromFile(
        getProjectFilesystem(), getCellPathResolver(), depFile);
  }

  private MessageHandler chooseMessageHandler(ExecutionContext executionContext) {
    UnusedDependenciesAction action = getUnusedDependenciesAction();

    if (action == UnusedDependenciesAction.FAIL) {
      return new ConcatenatingMessageHandler();
    }
    if (getUnusedDependenciesAction() == UnusedDependenciesAction.WARN) {
      return new ConsoleMessageHandler(executionContext.getBuckEventBus());
    }
    throw new IllegalStateException("Invalid action: " + action);
  }

  private void findUnusedDependenciesAndProcessMessages(
      MessageHandler messageHandler, ImmutableSet<Path> usedJars) {
    JavaLibraryDeps javaLibraryDeps = getJavaLibraryDeps();

    findUnusedDependenciesAndProcessMessages(
        messageHandler, usedJars, javaLibraryDeps.getDepTargets(), "deps");
    findUnusedDependenciesAndProcessMessages(
        messageHandler, usedJars, javaLibraryDeps.getProvidedDepTargets(), "provided_deps");
  }

  private void findUnusedDependenciesAndProcessMessages(
      MessageHandler messageHandler,
      ImmutableSet<Path> usedJars,
      Iterable<BuildTarget> targets,
      String dependencyType) {
    ImmutableSet<UnflavoredBuildTargetView> unusedDependencies =
        findUnusedDependencies(usedJars, targets);
    if (!unusedDependencies.isEmpty()) {
      processUnusedDependencies(messageHandler, dependencyType, unusedDependencies);
    }
  }

  private ImmutableSet<UnflavoredBuildTargetView> findUnusedDependencies(
      ImmutableSet<Path> usedJars, Iterable<BuildTarget> targets) {
    BuildRuleResolver buildRuleResolver = getBuildRuleResolver();
    SourcePathResolver sourcePathResolver = getSourcePathResolver();
    ImmutableSet.Builder<UnflavoredBuildTargetView> unusedDependencies = ImmutableSet.builder();

    for (BuildRule dependency : buildRuleResolver.getAllRules(targets)) {
      if (isUnusedDependency(dependency, usedJars, sourcePathResolver)) {
        unusedDependencies.add(dependency.getBuildTarget().getUnflavoredBuildTarget());
      }
    }

    return unusedDependencies.build();
  }

  private boolean isUnusedDependency(
      BuildRule dependency, ImmutableSet<Path> usedJars, SourcePathResolver sourcePathResolver) {
    if (!(dependency instanceof HasJavaAbi)) {
      return false;
    }

    final SourcePath dependencyOutput = dependency.getSourcePathToOutput();
    if (dependencyOutput == null) {
      return false;
    }

    final Path dependencyOutputPath = sourcePathResolver.getAbsolutePath(dependencyOutput);
    if (usedJars.contains(dependencyOutputPath)) {
      return false;
    }

    final Optional<Path> abiJarPath = getAbiJarPath(sourcePathResolver, (HasJavaAbi) dependency);
    if (abiJarPath.isPresent() && usedJars.contains(abiJarPath.get())) {
      return false;
    }

    if (dependency instanceof ExportDependencies) {
      for (BuildRule exportedDependency : ((ExportDependencies) dependency).getExportedDeps()) {
        if (!isUnusedDependency(exportedDependency, usedJars, sourcePathResolver)) {
          return false;
        }
      }
    }

    return true;
  }

  private Optional<BuildTarget> getAbiJarTarget(HasJavaAbi dependency) {
    Optional<BuildTarget> abiJarTarget = dependency.getSourceOnlyAbiJar();
    if (!abiJarTarget.isPresent()) {
      abiJarTarget = dependency.getAbiJar();
    }
    return abiJarTarget;
  }

  private Optional<Path> getAbiJarPath(
      SourcePathResolver sourcePathResolver, HasJavaAbi dependency) {
    Optional<BuildTarget> abiJarTarget = getAbiJarTarget(dependency);
    if (!abiJarTarget.isPresent()) {
      return Optional.empty();
    }

    BuildRule abiJarRule = getBuildRuleResolver().getRule(abiJarTarget.get());
    SourcePath abiJarOutput = abiJarRule.getSourcePathToOutput();
    if (abiJarOutput == null) {
      return Optional.empty();
    }

    return Optional.of(sourcePathResolver.getAbsolutePath(abiJarOutput));
  }

  private void processUnusedDependencies(
      MessageHandler messageHandler,
      String dependencyType,
      ImmutableSet<UnflavoredBuildTargetView> unusedDependencies) {
    BuildTarget buildTarget = getBuildTarget();
    String commandTemplate = "buildozer 'remove %s %s' %s";
    String commands =
        Joiner.on('\n')
            .join(
                unusedDependencies.stream()
                    .map(dep -> String.format(commandTemplate, dependencyType, dep, buildTarget))
                    .collect(Collectors.toList()));
    String messageTemplate =
        "Target %s is declared with unused targets in %s: \n%s\n\n"
            + "Please remove them. You may be able to use the following commands: \n%s\n";
    String deps = Joiner.on('\n').join(unusedDependencies);
    String message = String.format(messageTemplate, buildTarget, dependencyType, deps, commands);

    messageHandler.processMessage(message);
  }

  private void logDiagnosticsIfNeeded(MessageHandler messageHandler, Set<Path> usedJars) {
    if (messageHandler.encounteredMessage() && LOG.isLoggable(Level.INFO)) {
      LOG.info("Target: %s, usedJars:\n%s\n", getBuildTarget(), Joiner.on('\n').join(usedJars));
      LOG.info("Target: %s, javaLibraryDeps:\n%s\n", getBuildTarget(), getJavaLibraryDeps());
    }
  }

  @Override
  public String getShortName() {
    return "find_unused_dependencies";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("Find unused dependencies for %s", getBuildTarget());
  }

  /** A handler that processes messages about unused dependencies. */
  interface MessageHandler {
    void processMessage(String message);

    Optional<String> getFinalMessage();

    boolean encounteredMessage();
  }

  /** Consolidates all messages and provide concatenated message. */
  static class ConcatenatingMessageHandler implements MessageHandler {
    private final StringBuilder messageBuilder = new StringBuilder();

    @Override
    public void processMessage(String message) {
      messageBuilder.append(message);
    }

    @Override
    public Optional<String> getFinalMessage() {
      return messageBuilder.length() == 0
          ? Optional.empty()
          : Optional.of(messageBuilder.toString());
    }

    @Override
    public boolean encounteredMessage() {
      return messageBuilder.length() > 0;
    }
  }

  /** Writes messages to the console as warnings. */
  static class ConsoleMessageHandler implements MessageHandler {

    private final BuckEventBus buckEventBus;
    private boolean encounteredMessage = false;

    ConsoleMessageHandler(BuckEventBus buckEventBus) {
      this.buckEventBus = buckEventBus;
    }

    @Override
    public void processMessage(String message) {
      buckEventBus.post(ConsoleEvent.warning(message));
      encounteredMessage = true;
    }

    @Override
    public Optional<String> getFinalMessage() {
      return Optional.empty();
    }

    @Override
    public boolean encounteredMessage() {
      return encounteredMessage;
    }
  }
}
