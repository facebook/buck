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

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.cell.CellPathExtractor;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesAction;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedCellPathExtractor;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
public abstract class UnusedDependenciesFinder extends IsolatedStep {

  private static final Logger LOG = Logger.get(UnusedDependenciesFinder.class);

  public abstract BuildTarget getBuildTarget();

  public abstract ImmutableList<DependencyAndExportedDepsPath> getDeps();

  public abstract ImmutableList<DependencyAndExportedDepsPath> getProvidedDeps();

  public abstract ImmutableList<String> getExportedDeps();

  public abstract UnusedDependenciesAction getUnusedDependenciesAction();

  public abstract Optional<String> getBuildozerPath();

  public abstract boolean isOnlyPrintCommands();

  public abstract IsolatedCellPathExtractor getCellPathExtractor();

  public abstract CellNameResolver getCellNameResolver();

  public abstract RelPath getDepFile();

  public abstract AbsPath getRootPath();

  public abstract boolean doUltralightChecking();

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context) {
    Preconditions.checkState(getUnusedDependenciesAction() != UnusedDependenciesAction.IGNORE);

    ImmutableSet<AbsPath> usedJars =
        loadUsedJarPaths(getCellPathExtractor(), getCellNameResolver());
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

  private ImmutableSet<AbsPath> loadUsedJarPaths(
      CellPathExtractor cellPathExtractor, CellNameResolver cellNameResolver) {
    AbsPath depFile = toAbsPath(getDepFile());
    if (!depFile.toFile().exists()) {
      return ImmutableSet.of();
    }
    return DefaultClassUsageFileReader.loadUsedJarsFromFile(
        getRootPath(), cellPathExtractor, cellNameResolver, depFile, doUltralightChecking());
  }

  private MessageHandler chooseMessageHandler(IsolatedExecutionContext executionContext) {
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
      MessageHandler messageHandler, ImmutableSet<AbsPath> usedJars) {
    findUnusedDependenciesAndProcessMessages(messageHandler, usedJars, getDeps(), "deps");
    findUnusedDependenciesAndProcessMessages(
        messageHandler, usedJars, getProvidedDeps(), "provided_deps");
  }

  private void findUnusedDependenciesAndProcessMessages(
      MessageHandler messageHandler,
      ImmutableSet<AbsPath> usedJars,
      ImmutableList<DependencyAndExportedDepsPath> targets,
      String dependencyType) {
    ImmutableSet<String> unusedDependencies = findUnusedDependencies(usedJars, targets);

    if (!unusedDependencies.isEmpty()) {
      processUnusedDependencies(messageHandler, dependencyType, unusedDependencies);
    }
  }

  private ImmutableSet<String> findUnusedDependencies(
      ImmutableSet<AbsPath> usedJars, ImmutableList<DependencyAndExportedDepsPath> targets) {
    ImmutableSet.Builder<String> unusedDependencies = ImmutableSet.builder();

    ImmutableSet<String> firstOrderDeps =
        Stream.concat(
                targets.stream().map(x -> x.dependency.buildTarget), getExportedDeps().stream())
            .collect(ImmutableSet.toImmutableSet());
    for (DependencyAndExportedDepsPath target : targets) {
      if (isUnusedDependencyIncludingExportedDeps(target, usedJars, firstOrderDeps)) {
        unusedDependencies.add(target.dependency.buildTarget);
      }
    }

    return unusedDependencies.build();
  }

  private boolean isUnusedDependencyIncludingExportedDeps(
      DependencyAndExportedDepsPath dependency,
      ImmutableSet<AbsPath> usedJars,
      ImmutableSet<String> firstOrderDepTargets) {
    if (isUsedDependency(dependency.dependency, usedJars)) {
      return false;
    }

    for (DependencyAndExportedDepsPath exportedDependency : dependency.exportedDeps) {
      if (isUsedDependencyIncludingExportedDeps(
          exportedDependency, usedJars, firstOrderDepTargets)) {
        return false;
      }
    }

    return true;
  }

  private boolean isUsedDependencyIncludingExportedDeps(
      DependencyAndExportedDepsPath exportedDep,
      ImmutableSet<AbsPath> usedJars,
      ImmutableSet<String> firstOrderDepTargets) {
    if (!firstOrderDepTargets.contains(exportedDep.dependency.buildTarget)
        && isUsedDependency(exportedDep.dependency, usedJars)) {
      return true;
    }

    for (DependencyAndExportedDepsPath exportedDependency : exportedDep.exportedDeps) {
      if (isUsedDependencyIncludingExportedDeps(
          exportedDependency, usedJars, firstOrderDepTargets)) {
        return true;
      }
    }

    return false;
  }

  private boolean isUsedDependency(BuildTargetAndPaths dependency, ImmutableSet<AbsPath> usedJars) {
    if (dependency.fullJarPath != null && usedJars.contains(toAbsPath(dependency.fullJarPath))) {
      return true;
    }
    return dependency.abiPath != null && usedJars.contains(toAbsPath(dependency.abiPath));
  }

  private AbsPath toAbsPath(RelPath relPath) {
    return ProjectFilesystemUtils.getAbsPathForRelativePath(getRootPath(), relPath.getPath());
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

    String message;

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

  private void logDiagnosticsIfNeeded(MessageHandler messageHandler, Set<AbsPath> usedJars) {
    if (messageHandler.encounteredMessage() && LOG.isLoggable(Level.INFO)) {
      LOG.info("Target: %s, usedJars:\n%s\n", getBuildTarget(), Joiner.on('\n').join(usedJars));
      LOG.info(
          "Target: %s, deps are:\n%s\nProvided deps are:\n%s\n",
          getBuildTarget(),
          Joiner.on('\n')
              .join(
                  getDeps().stream()
                      .map(dep -> dep.dependency.buildTarget)
                      .collect(Collectors.toList())),
          Joiner.on('\n')
              .join(
                  getProvidedDeps().stream()
                      .map(dep -> dep.dependency.buildTarget)
                      .collect(Collectors.toList())));
    }
  }

  @Override
  public String getShortName() {
    return "find_unused_dependencies";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
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

  /** Recursive hierarchy of a single build target and its exported deps. */
  static class DependencyAndExportedDepsPath {
    private final BuildTargetAndPaths dependency;
    private final ImmutableList<DependencyAndExportedDepsPath> exportedDeps;

    DependencyAndExportedDepsPath(
        BuildTargetAndPaths dependency, ImmutableList<DependencyAndExportedDepsPath> exportedDeps) {
      this.dependency = dependency;
      this.exportedDeps = exportedDeps;
    }
  }

  /** Holder for a build target string and the source paths of its output. */
  static class BuildTargetAndPaths {
    private final String buildTarget;
    private final @Nullable RelPath fullJarPath;
    private final @Nullable RelPath abiPath;

    BuildTargetAndPaths(
        String buildTarget, @Nullable RelPath fullJarSourcePath, @Nullable RelPath abiSourcePath) {
      this.buildTarget = buildTarget;
      this.fullJarPath = fullJarSourcePath;
      this.abiPath = abiSourcePath;
    }
  }
}
