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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.ExportDependencies;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.CalculateAbi;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesAction;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.logging.Level;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * The step that reports dependencies not used during Java compilation.
 *
 * <p>It uses class usage map produced during compilation and compares it to the outputs of the
 * dependencies specified in Java libraries. The entries from classpath not used during compilation
 * are resolved to the corresponding targets and reported back to the user either as an error (which
 * halt the build) or as a warning.
 */
@BuckStyleValue
public abstract class UnusedDependenciesFinder implements Step {

  private static final Logger LOG = Logger.get(UnusedDependenciesFinder.class);

  public abstract BuildTarget getBuildTarget();

  public abstract ProjectFilesystem getProjectFilesystem();

  public abstract Path getDepFileRelativePath();

  public abstract ImmutableList<DependencyAndExportedDeps> getDeps();

  public abstract ImmutableList<DependencyAndExportedDeps> getProvidedDeps();

  public abstract SourcePathResolverAdapter getSourcePathResolver();

  public abstract UnusedDependenciesAction getUnusedDependenciesAction();

  public abstract Optional<String> getBuildozerPath();

  public abstract boolean isOnlyPrintCommands();

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    Preconditions.checkState(getUnusedDependenciesAction() != UnusedDependenciesAction.IGNORE);

    ImmutableSet<Path> usedJars = loadUsedJarPaths(context.getCellPathResolver());
    MessageHandler messageHandler = chooseMessageHandler(context);

    findUnusedDependenciesAndProcessMessages(messageHandler, usedJars);

    logDiagnosticsIfNeeded(messageHandler, usedJars);

    Optional<String> message = messageHandler.getFinalMessage();
    if (message.isPresent()) {
      return StepExecutionResult.builder()
          .setExitCode(StepExecutionResults.ERROR_EXIT_CODE)
          .setStderr(message)
          .build();
    } else {
      return StepExecutionResults.SUCCESS;
    }
  }

  private ImmutableSet<Path> loadUsedJarPaths(CellPathResolver cellPathResolver)
      throws IOException {
    Path depFile = getProjectFilesystem().getPathForRelativePath(getDepFileRelativePath());
    if (!depFile.toFile().exists()) {
      return ImmutableSet.of();
    }

    return DefaultClassUsageFileReader.loadUsedJarsFromFile(
        getProjectFilesystem(), cellPathResolver, depFile);
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
    findUnusedDependenciesAndProcessMessages(messageHandler, usedJars, getDeps(), "deps");
    findUnusedDependenciesAndProcessMessages(
        messageHandler, usedJars, getProvidedDeps(), "provided_deps");
  }

  private void findUnusedDependenciesAndProcessMessages(
      MessageHandler messageHandler,
      ImmutableSet<Path> usedJars,
      ImmutableList<DependencyAndExportedDeps> targets,
      String dependencyType) {
    ImmutableSet<String> unusedDependencies = findUnusedDependencies(usedJars, targets);

    if (!unusedDependencies.isEmpty()) {
      processUnusedDependencies(messageHandler, dependencyType, unusedDependencies);
    }
  }

  private ImmutableSet<String> findUnusedDependencies(
      ImmutableSet<Path> usedJars, ImmutableList<DependencyAndExportedDeps> targets) {
    SourcePathResolverAdapter sourcePathResolverAdapter = getSourcePathResolver();
    ImmutableSet.Builder<String> unusedDependencies = ImmutableSet.builder();

    ImmutableSet<String> firstOrderDeps =
        targets.stream().map(x -> x.dependency.buildTarget).collect(ImmutableSet.toImmutableSet());
    for (DependencyAndExportedDeps target : targets) {
      if (isUnusedDependencyIncludingExportedDeps(
          target, usedJars, sourcePathResolverAdapter, firstOrderDeps)) {
        unusedDependencies.add(target.dependency.buildTarget);
      }
    }

    return unusedDependencies.build();
  }

  private boolean isUnusedDependencyIncludingExportedDeps(
      DependencyAndExportedDeps dependency,
      ImmutableSet<Path> usedJars,
      SourcePathResolverAdapter sourcePathResolverAdapter,
      ImmutableSet<String> firstOrderDepTargets) {
    if (isUsedDependency(dependency.dependency, usedJars, sourcePathResolverAdapter)) {
      return false;
    }

    for (DependencyAndExportedDeps exportedDependency : dependency.exportedDeps) {
      if (isUsedDependencyIncludingExportedDeps(
          exportedDependency, usedJars, sourcePathResolverAdapter, firstOrderDepTargets)) {
        return false;
      }
    }

    return true;
  }

  private boolean isUsedDependencyIncludingExportedDeps(
      DependencyAndExportedDeps exportedDep,
      ImmutableSet<Path> usedJars,
      SourcePathResolverAdapter sourcePathResolverAdapter,
      ImmutableSet<String> firstOrderDepTargets) {
    if (!firstOrderDepTargets.contains(exportedDep.dependency.buildTarget)
        && isUsedDependency(exportedDep.dependency, usedJars, sourcePathResolverAdapter)) {
      return true;
    }

    for (DependencyAndExportedDeps exportedDependency : exportedDep.exportedDeps) {
      if (isUsedDependencyIncludingExportedDeps(
          exportedDependency, usedJars, sourcePathResolverAdapter, firstOrderDepTargets)) {
        return true;
      }
    }

    return false;
  }

  private boolean isUsedDependency(
      BuildTargetAndSourcePaths dependency,
      ImmutableSet<Path> usedJars,
      SourcePathResolverAdapter sourcePathResolverAdapter) {
    final @Nullable SourcePath dependencyOutput = dependency.fullJarSourcePath;
    if (dependencyOutput != null) {
      final Path dependencyOutputPath = sourcePathResolverAdapter.getAbsolutePath(dependencyOutput);
      if (usedJars.contains(dependencyOutputPath)) {
        return true;
      }
    }

    final @Nullable SourcePath dependencyAbiOutput = dependency.abiSourcePath;
    if (dependencyAbiOutput != null) {
      final Path dependencyAbiOutputPath =
          sourcePathResolverAdapter.getAbsolutePath(dependencyAbiOutput);
      if (usedJars.contains(dependencyAbiOutputPath)) {
        return true;
      }
    }

    return false;
  }

  private void processUnusedDependencies(
      MessageHandler messageHandler,
      String dependencyType,
      ImmutableSet<String> unusedDependencies) {
    BuildTarget buildTarget = getBuildTarget();
    String commandTemplate = "%s 'remove %s %s' %s";
    String commands =
        Joiner.on('\n')
            .join(
                unusedDependencies.stream()
                    .map(
                        dep ->
                            String.format(
                                commandTemplate,
                                getBuildozerPath().orElse("buildozer"),
                                dependencyType,
                                dep,
                                buildTarget))
                    .collect(Collectors.toList()));

    final String message;

    if (isOnlyPrintCommands()) {
      message = commands;
    } else {
      String messageTemplate =
          "Target %s is declared with unused targets in %s: \n%s\n\n"
              + "Please remove them. You may be able to use the following commands: \n%s\n\n"
              + "If you are sure that these targets are required, then you may add them as "
              + "`runtime_deps` instead and they will no longer be detected as unused.\n";
      String deps = Joiner.on('\n').join(unusedDependencies);
      message = String.format(messageTemplate, buildTarget, dependencyType, deps, commands);
    }

    messageHandler.processMessage(message);
  }

  private void logDiagnosticsIfNeeded(MessageHandler messageHandler, Set<Path> usedJars) {
    if (messageHandler.encounteredMessage() && LOG.isLoggable(Level.INFO)) {
      LOG.info("Target: %s, usedJars:\n%s\n", getBuildTarget(), Joiner.on('\n').join(usedJars));
      LOG.info(
          "Target: %s, deps are:\n%s\nProvided deps are:\n%s\n",
          getBuildTarget(),
          Joiner.on('\n')
              .join(
                  getDeps().stream()
                      .map(dep -> dep.dependency.buildTarget.toString())
                      .collect(Collectors.toList())),
          Joiner.on('\n')
              .join(
                  getProvidedDeps().stream()
                      .map(dep -> dep.dependency.buildTarget.toString())
                      .collect(Collectors.toList())));
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

  static ImmutableList<DependencyAndExportedDeps> getDependencies(
      BuildRuleResolver buildRuleResolver, SortedSet<BuildRule> targets) {
    ImmutableList.Builder<DependencyAndExportedDeps> builder = ImmutableList.builder();
    for (BuildRule rule : targets) {
      BuildTargetAndSourcePaths targetAndSourcePaths =
          getBuildTargetAndSourcePaths(rule, buildRuleResolver);
      if (targetAndSourcePaths == null) {
        continue;
      }

      ImmutableList<DependencyAndExportedDeps> exportedDeps =
          rule instanceof ExportDependencies
              ? getDependencies(buildRuleResolver, ((ExportDependencies) rule).getExportedDeps())
              : ImmutableList.of();
      builder.add(new DependencyAndExportedDeps(targetAndSourcePaths, exportedDeps));
    }

    return builder.build();
  }

  @Nullable
  private static BuildTargetAndSourcePaths getBuildTargetAndSourcePaths(
      BuildRule rule, BuildRuleResolver buildRuleResolver) {
    if (!(rule instanceof JavaLibrary || rule instanceof CalculateAbi)) {
      return null;
    }

    if (rule instanceof JavaLibrary && ((JavaLibrary) rule).neverMarkAsUnusedDependency()) {
      return null;
    }

    final SourcePath ruleOutput = rule.getSourcePathToOutput();
    final SourcePath abiRuleOutput = getAbiPath(buildRuleResolver, (HasJavaAbi) rule);
    return new BuildTargetAndSourcePaths(
        rule.getBuildTarget().getUnconfiguredBuildTarget().toString(), ruleOutput, abiRuleOutput);
  }

  @Nullable
  private static SourcePath getAbiPath(BuildRuleResolver buildRuleResolver, HasJavaAbi rule) {
    Optional<BuildTarget> abiJarTarget = getAbiJarTarget(rule);
    if (!abiJarTarget.isPresent()) {
      return null;
    }

    Optional<BuildRule> abiJarRule = buildRuleResolver.getRuleOptional(abiJarTarget.get());
    if (!abiJarRule.isPresent()) {
      return null;
    }

    return abiJarRule.get().getSourcePathToOutput();
  }

  private static Optional<BuildTarget> getAbiJarTarget(HasJavaAbi dependency) {
    Optional<BuildTarget> abiJarTarget = dependency.getSourceOnlyAbiJar();
    if (!abiJarTarget.isPresent()) {
      abiJarTarget = dependency.getAbiJar();
    }
    return abiJarTarget;
  }

  /** Holder for a build target string and the source paths of its output. */
  static class BuildTargetAndSourcePaths implements AddsToRuleKey {
    @AddToRuleKey private final String buildTarget;
    @AddToRuleKey private final @Nullable SourcePath fullJarSourcePath;
    @AddToRuleKey private final @Nullable SourcePath abiSourcePath;

    BuildTargetAndSourcePaths(
        String buildTarget,
        @Nullable SourcePath fullJarSourcePath,
        @Nullable SourcePath abiSourcePath) {
      this.buildTarget = buildTarget;
      this.fullJarSourcePath = fullJarSourcePath;
      this.abiSourcePath = abiSourcePath;
    }
  }

  /** Recursive hierarchy of a single build target and its exported deps. */
  static class DependencyAndExportedDeps implements AddsToRuleKey {
    @AddToRuleKey private final BuildTargetAndSourcePaths dependency;
    @AddToRuleKey private final ImmutableList<DependencyAndExportedDeps> exportedDeps;

    DependencyAndExportedDeps(
        BuildTargetAndSourcePaths dependency,
        ImmutableList<DependencyAndExportedDeps> exportedDeps) {
      this.dependency = dependency;
      this.exportedDeps = exportedDeps;
    }
  }
}
