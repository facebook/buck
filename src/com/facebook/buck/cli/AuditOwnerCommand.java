/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.query.QueryException;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreExceptions;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AuditOwnerCommand extends AbstractCommand {

  @VisibleForTesting
  static final class OwnersReport {
    final ImmutableSetMultimap<TargetNode<?>, Path> owners;
    final ImmutableSet<Path> inputsWithNoOwners;
    final ImmutableSet<String> nonExistentInputs;
    final ImmutableSet<String> nonFileInputs;

    public OwnersReport(SetMultimap<TargetNode<?>, Path> owners,
        Set<Path> inputsWithNoOwners,
        Set<String> nonExistentInputs,
        Set<String> nonFileInputs) {
      this.owners = ImmutableSetMultimap.copyOf(owners);
      this.inputsWithNoOwners = ImmutableSet.copyOf(inputsWithNoOwners);
      this.nonExistentInputs = ImmutableSet.copyOf(nonExistentInputs);
      this.nonFileInputs = ImmutableSet.copyOf(nonFileInputs);
    }

    public static OwnersReport emptyReport() {
      return new OwnersReport(
          ImmutableSetMultimap.<TargetNode<?>, Path>of(),
          Sets.<Path>newHashSet(),
          Sets.<String>newHashSet(),
          Sets.<String>newHashSet());
    }

    public OwnersReport updatedWith(OwnersReport other) {
      SetMultimap<TargetNode<?>, Path> updatedOwners =
          TreeMultimap.create(owners);
      updatedOwners.putAll(other.owners);

      return new OwnersReport(
          updatedOwners,
          Sets.intersection(inputsWithNoOwners, other.inputsWithNoOwners),
          Sets.union(nonExistentInputs, other.nonExistentInputs),
          Sets.union(nonFileInputs, other.nonFileInputs));
    }
  }

  @Option(name = "--json",
      usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  /**
   * @return relative paths under the project root
   */
  public static Iterable<Path> getArgumentsAsPaths(Path projectRoot, Iterable<String> args)
      throws IOException {
    return PathArguments.getCanonicalFilesUnderProjectRoot(projectRoot, args)
        .relativePathsUnderProjectRoot;
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (params.getConsole().getAnsi().isAnsiTerminal()) {
      params.getBuckEventBus().post(ConsoleEvent.info(
          "'buck audit owner' is deprecated. Please use 'buck query' instead. e.g.\n\t%s\n\n" +
              "The query language is documented at https://buckbuild.com/command/query.html",
          QueryCommand.buildAuditOwnerQueryExpression(getArguments(), shouldGenerateJsonOutput())));
    }

    BuckQueryEnvironment env = new BuckQueryEnvironment(params, getEnableParserProfiling());
    try (CommandThreadManager pool = new CommandThreadManager(
        "Audit",
        getConcurrencyLimit(params.getBuckConfig()))) {
      return QueryCommand.runMultipleQuery(
          params,
          env,
          pool.getExecutor(),
          "owner('%s')",
          getArguments(),
          shouldGenerateJsonOutput());
    } catch (QueryException e) {
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      params.getBuckEventBus().post(
          ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return 1;
    }
  }

  static OwnersReport buildOwnersReport(
      CommandRunnerParams params,
      BuildFileTree buildFileTree,
      ListeningExecutorService executor,
      Iterable<String> arguments)
      throws IOException, InterruptedException, BuildFileParseException, BuildTargetException {
    ProjectFilesystem cellFilesystem = params.getCell().getFilesystem();
    final Path rootPath = cellFilesystem.getRootPath();
    Preconditions.checkState(rootPath.isAbsolute());
    Map<Path, ImmutableSet<TargetNode<?>>> targetNodes = Maps.newHashMap();
    OwnersReport report = OwnersReport.emptyReport();

    for (Path filePath : getArgumentsAsPaths(rootPath, arguments)) {
      Optional<Path> basePath = buildFileTree.getBasePathOfAncestorTarget(filePath);
      if (!basePath.isPresent()) {
        report = report.updatedWith(
            new OwnersReport(
                ImmutableSetMultimap.<TargetNode<?>, Path>of(),
                /* inputWithNoOwners */ ImmutableSet.of(filePath),
                Sets.<String>newHashSet(),
                Sets.<String>newHashSet()));
        continue;
      }

      Path buckFile = cellFilesystem.resolve(basePath.get())
          .resolve(params.getCell().getBuildFileName());
      Preconditions.checkState(cellFilesystem.exists(buckFile));

      // Parse buck files and load target nodes.
      if (!targetNodes.containsKey(buckFile)) {
        try {
          targetNodes.put(
              buckFile,
              params.getParser().getAllTargetNodes(
                  params.getBuckEventBus(),
                  params.getCell(),
                  /* enable profiling */ false,
                  executor,
                  buckFile));
        } catch (BuildFileParseException e) {
          Path targetBasePath = MorePaths.relativize(rootPath, rootPath.resolve(basePath.get()));
          String targetBaseName = "//" + MorePaths.pathWithUnixSeparators(targetBasePath);

          params
              .getConsole()
              .getStdErr()
              .format("Could not parse build targets for %s", targetBaseName);
          throw e;
        }
      }

      for (TargetNode<?> targetNode : targetNodes.get(buckFile)) {
        report = report.updatedWith(
            generateOwnersReport(
                params,
                targetNode,
                ImmutableList.of(filePath.toString())));
      }
    }
    return report;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @VisibleForTesting
  static OwnersReport generateOwnersReport(
      CommandRunnerParams params,
      TargetNode<?> targetNode,
      Iterable<String> filePaths) {

    // Process arguments assuming they are all relative file paths.
    Set<Path> inputs = Sets.newHashSet();
    Set<String> nonExistentInputs = Sets.newHashSet();
    Set<String> nonFileInputs = Sets.newHashSet();

    for (String filePath : filePaths) {
      File file = params.getCell().getFilesystem().getFileForRelativePath(filePath);
      if (!file.exists()) {
        nonExistentInputs.add(filePath);
      } else if (!file.isFile()) {
        nonFileInputs.add(filePath);
      } else {
        inputs.add(Paths.get(filePath));
      }
    }

    // Try to find owners for each valid and existing file.
    Set<Path> inputsWithNoOwners = Sets.newHashSet(inputs);
    SetMultimap<TargetNode<?>, Path> owners = TreeMultimap.create();
    for (final Path commandInput : inputs) {
      Predicate<Path> startsWith = new Predicate<Path>() {
        @Override
        public boolean apply(Path input) {
          return !commandInput.equals(input) && commandInput.startsWith(input);
        }
      };

      Set<Path> ruleInputs = targetNode.getInputs();
      if (ruleInputs.contains(commandInput) ||
          FluentIterable.from(ruleInputs).anyMatch(startsWith)) {
        inputsWithNoOwners.remove(commandInput);
        owners.put(targetNode, commandInput);
      }
    }

    return new OwnersReport(owners, inputsWithNoOwners, nonExistentInputs, nonFileInputs);
  }

  @Override
  public String getShortDescription() {
    return "prints targets that own specified files";
  }

}
