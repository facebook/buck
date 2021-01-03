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

package com.facebook.buck.step.isolatedsteps.java;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.cell.CellPathExtractor;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.jvm.java.DefaultClassUsageFileReader;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesAction;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.common.cellpathextractor.IsolatedCellPathExtractor;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Objects;
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
  private static final String LINE_SEPARATOR = System.lineSeparator();

  public abstract String getFullyQualifiedBuildTargetName();

  public abstract ImmutableList<DependencyAndExportedDepsPath> getDeps();

  public abstract ImmutableList<DependencyAndExportedDepsPath> getProvidedDeps();

  public abstract ImmutableList<String> getExportedDeps();

  public abstract UnusedDependenciesAction getUnusedDependenciesAction();

  public abstract Optional<String> getBuildozerPath();

  public abstract boolean isOnlyPrintCommands();

  public abstract ImmutableMap<CanonicalCellName, RelPath> getCellToPathMappings();

  public abstract RelPath getDepFile();

  public abstract boolean doUltralightChecking();

  public static ImmutableUnusedDependenciesFinder of(
      String fullyQualifiedBuildTargetName,
      ImmutableList<DependencyAndExportedDepsPath> deps,
      ImmutableList<DependencyAndExportedDepsPath> providedDeps,
      ImmutableList<String> exportedDeps,
      JavaBuckConfig.UnusedDependenciesAction unusedDependenciesAction,
      Optional<String> buildozerPath,
      boolean onlyPrintCommands,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      RelPath depFile,
      boolean doUltralightChecking) {
    return ImmutableUnusedDependenciesFinder.ofImpl(
        fullyQualifiedBuildTargetName,
        deps,
        providedDeps,
        exportedDeps,
        unusedDependenciesAction,
        buildozerPath,
        onlyPrintCommands,
        cellToPathMappings,
        depFile,
        doUltralightChecking);
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context) {
    Preconditions.checkState(getUnusedDependenciesAction() != UnusedDependenciesAction.IGNORE);

    AbsPath ruleCellRoot = context.getRuleCellRoot();
    CellPathExtractor cellPathExtractor =
        IsolatedCellPathExtractor.of(ruleCellRoot, getCellToPathMappings());

    ImmutableSet<AbsPath> usedJars = loadUsedJarPaths(ruleCellRoot, cellPathExtractor);
    MessageHandler messageHandler = chooseMessageHandler(context);

    findUnusedDependenciesAndProcessMessages(ruleCellRoot, messageHandler, usedJars);

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
      AbsPath root, CellPathExtractor cellPathExtractor) {
    AbsPath depFile = toAbsPath(root, getDepFile());
    if (!depFile.toFile().exists()) {
      return ImmutableSet.of();
    }
    return DefaultClassUsageFileReader.loadUsedJarsFromFile(
        root, cellPathExtractor, depFile, doUltralightChecking());
  }

  private MessageHandler chooseMessageHandler(IsolatedExecutionContext executionContext) {
    UnusedDependenciesAction action = getUnusedDependenciesAction();

    if (action == UnusedDependenciesAction.FAIL) {
      return new ConcatenatingMessageHandler();
    }
    if (getUnusedDependenciesAction() == UnusedDependenciesAction.WARN) {
      return new ConsoleMessageHandler(executionContext.getIsolatedEventBus());
    }
    throw new IllegalStateException("Invalid action: " + action);
  }

  private void findUnusedDependenciesAndProcessMessages(
      AbsPath root, MessageHandler messageHandler, ImmutableSet<AbsPath> usedJars) {
    findUnusedDependenciesAndProcessMessages(root, messageHandler, usedJars, getDeps(), "deps");
    findUnusedDependenciesAndProcessMessages(
        root, messageHandler, usedJars, getProvidedDeps(), "provided_deps");
  }

  private void findUnusedDependenciesAndProcessMessages(
      AbsPath root,
      MessageHandler messageHandler,
      ImmutableSet<AbsPath> usedJars,
      ImmutableList<DependencyAndExportedDepsPath> targets,
      String dependencyType) {
    ImmutableSet<String> unusedDependencies = findUnusedDependencies(root, usedJars, targets);

    if (!unusedDependencies.isEmpty()) {
      processUnusedDependencies(messageHandler, dependencyType, unusedDependencies);
    }
  }

  private ImmutableSet<String> findUnusedDependencies(
      AbsPath root,
      ImmutableSet<AbsPath> usedJars,
      ImmutableList<DependencyAndExportedDepsPath> targets) {
    ImmutableSet.Builder<String> unusedDependencies = ImmutableSet.builder();

    ImmutableSet<String> firstOrderDeps =
        Stream.concat(
                targets.stream().map(x -> x.getDependency().getBuildTarget()),
                getExportedDeps().stream())
            .collect(ImmutableSet.toImmutableSet());
    for (DependencyAndExportedDepsPath target : targets) {
      if (isUnusedDependencyIncludingExportedDeps(root, target, usedJars, firstOrderDeps)) {
        unusedDependencies.add(target.getDependency().getBuildTarget());
      }
    }

    return unusedDependencies.build();
  }

  private boolean isUnusedDependencyIncludingExportedDeps(
      AbsPath root,
      DependencyAndExportedDepsPath dependency,
      ImmutableSet<AbsPath> usedJars,
      ImmutableSet<String> firstOrderDepTargets) {
    if (isUsedDependency(root, dependency.getDependency(), usedJars)) {
      return false;
    }

    for (DependencyAndExportedDepsPath exportedDependency : dependency.getExportedDeps()) {
      if (isUsedDependencyIncludingExportedDeps(
          root, exportedDependency, usedJars, firstOrderDepTargets)) {
        return false;
      }
    }

    return true;
  }

  private boolean isUsedDependencyIncludingExportedDeps(
      AbsPath root,
      DependencyAndExportedDepsPath exportedDep,
      ImmutableSet<AbsPath> usedJars,
      ImmutableSet<String> firstOrderDepTargets) {
    if (!firstOrderDepTargets.contains(exportedDep.getDependency().getBuildTarget())
        && isUsedDependency(root, exportedDep.getDependency(), usedJars)) {
      return true;
    }

    for (DependencyAndExportedDepsPath exportedDependency : exportedDep.getExportedDeps()) {
      if (isUsedDependencyIncludingExportedDeps(
          root, exportedDependency, usedJars, firstOrderDepTargets)) {
        return true;
      }
    }

    return false;
  }

  private boolean isUsedDependency(
      AbsPath root, BuildTargetAndPaths dependency, ImmutableSet<AbsPath> usedJars) {
    if (dependency.getFullJarPath() != null
        && usedJars.contains(toAbsPath(root, dependency.getFullJarPath()))) {
      return true;
    }
    return dependency.getAbiPath() != null
        && usedJars.contains(toAbsPath(root, dependency.getAbiPath()));
  }

  private AbsPath toAbsPath(AbsPath root, RelPath relPath) {
    return ProjectFilesystemUtils.getAbsPathForRelativePath(root, relPath.getPath());
  }

  private void processUnusedDependencies(
      MessageHandler messageHandler,
      String dependencyType,
      ImmutableSet<String> unusedDependencies) {
    String buildTarget = getFullyQualifiedBuildTargetName();
    String commandTemplate = "%s 'remove %s %s' %s";
    String commands =
        unusedDependencies.stream()
            .map(
                dep ->
                    String.format(
                        commandTemplate,
                        getBuildozerPath().orElse("buildozer"),
                        dependencyType,
                        dep,
                        buildTarget))
            .collect(Collectors.joining(LINE_SEPARATOR));

    String message;

    if (isOnlyPrintCommands()) {
      message = commands;
    } else {
      String messageTemplate =
          "Target %s is declared with unused targets in %s: %n%s%n%n"
              + "Please remove them. You may be able to use the following commands: %n%s%n%n"
              + "If you are sure that these targets are required, then you may add them as "
              + "`runtime_deps` instead and they will no longer be detected as unused.%n";
      String deps = String.join(LINE_SEPARATOR, unusedDependencies);
      message = String.format(messageTemplate, buildTarget, dependencyType, deps, commands);
    }

    messageHandler.processMessage(message);
  }

  private void logDiagnosticsIfNeeded(MessageHandler messageHandler, Set<AbsPath> usedJars) {
    if (messageHandler.encounteredMessage() && LOG.isLoggable(Level.INFO)) {
      String buildTarget = getFullyQualifiedBuildTargetName();
      LOG.info(
          "Target: %s, usedJars:%n%s%n",
          buildTarget,
          usedJars.stream().map(Objects::toString).collect(Collectors.joining(LINE_SEPARATOR)));
      LOG.info(
          "Target: %s, deps are:%n%s%nProvided deps are:%n%s%n",
          buildTarget,
          getDeps().stream()
              .map(dep -> dep.getDependency().getBuildTarget())
              .collect(Collectors.joining(LINE_SEPARATOR)),
          getProvidedDeps().stream()
              .map(dep -> dep.getDependency().getBuildTarget())
              .collect(Collectors.joining(LINE_SEPARATOR)));
    }
  }

  @Override
  public String getShortName() {
    return "find_unused_dependencies";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return String.format("Find unused dependencies for %s", getFullyQualifiedBuildTargetName());
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

    private final IsolatedEventBus buckEventBus;
    private boolean encounteredMessage = false;

    ConsoleMessageHandler(IsolatedEventBus buckEventBus) {
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
  @BuckStyleValue
  public abstract static class DependencyAndExportedDepsPath {

    public abstract BuildTargetAndPaths getDependency();

    public abstract ImmutableList<DependencyAndExportedDepsPath> getExportedDeps();

    public static DependencyAndExportedDepsPath of(
        BuildTargetAndPaths dependency, ImmutableList<DependencyAndExportedDepsPath> exportedDeps) {
      return ImmutableDependencyAndExportedDepsPath.ofImpl(dependency, exportedDeps);
    }
  }

  /** Holder for a build target string and the source paths of its output. */
  @BuckStyleValue
  public abstract static class BuildTargetAndPaths {

    public abstract String getBuildTarget();

    @Nullable
    public abstract RelPath getFullJarPath();

    @Nullable
    public abstract RelPath getAbiPath();

    public static BuildTargetAndPaths of(
        String buildTarget, @Nullable RelPath fullJarPath, @Nullable RelPath abiPath) {
      return ImmutableBuildTargetAndPaths.ofImpl(buildTarget, fullJarPath, abiPath);
    }
  }
}
