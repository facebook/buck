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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.engine.BuildExecutorRunner;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.Serializer;
import com.facebook.buck.rules.modern.Serializer.Delegate;
import com.facebook.buck.rules.modern.builders.FileTreeBuilder.InputFile;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.function.ThrowingFunction;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * This wraps an IsolatedExecution implementation into a BuildRuleStrategy implementation. This
 * handles setting up all the input files for isolated execution (the rule's inputs, the serialized
 * rule data, serialized buck configs, etc). Handles recording outputs, etc.
 */
public class IsolatedExecutionStrategy extends AbstractModernBuildRuleStrategy {
  private final IsolatedExecution executionStrategy;
  private final CellPathResolver cellResolver;
  private final Cell rootCell;
  private final ThrowingFunction<Path, HashCode, IOException> fileHasher;
  private final Serializer serializer;
  private final Map<Optional<String>, byte[]> cellToConfig;
  private final Map<Optional<String>, String> configHashes;
  private final Path cellPathPrefix;
  private final Set<Optional<String>> cellNames;
  private final Map<HashCode, Node> nodeMap;

  IsolatedExecutionStrategy(
      IsolatedExecution executionStrategy,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher) {
    this.executionStrategy = executionStrategy;
    this.cellResolver = cellResolver;
    this.rootCell = rootCell;
    this.fileHasher = fileHasher;
    this.nodeMap = new ConcurrentHashMap<>();

    Delegate delegate =
        (instance, data, children) -> {
          HashCode hash = Hashing.sha1().hashBytes(data);
          Node node =
              new Node(
                  data,
                  children
                      .stream()
                      .collect(
                          ImmutableSortedMap.toImmutableSortedMap(
                              Ordering.natural(), HashCode::toString, nodeMap::get)));
          nodeMap.put(hash, node);
          return hash;
        };
    this.serializer = new Serializer(ruleFinder, cellResolver, delegate);

    this.cellNames =
        rootCell
            .getCellProvider()
            .getLoadedCells()
            .values()
            .stream()
            .map(Cell::getCanonicalName)
            .collect(ImmutableSet.toImmutableSet());

    this.cellToConfig =
        cellNames
            .stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    v -> v,
                    name ->
                        serializeConfig(
                            rootCell
                                .getCellProvider()
                                .getCellByPath(cellResolver.getCellPath(name).get())
                                .getBuckConfig())));

    HashFunction hasher = Hashing.sha1();
    this.configHashes =
        cellToConfig
            .entrySet()
            .stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    entry -> entry.getKey(),
                    entry -> hasher.hashBytes(entry.getValue()).toString()));

    this.cellPathPrefix =
        MorePaths.splitOnCommonPrefix(
                cellNames
                    .stream()
                    .map(name -> cellResolver.getCellPath(name).get())
                    .collect(ImmutableList.toImmutableList()))
            .get()
            .getFirst();
  }

  private Path getPrefixRelativeCellPath(Optional<String> name) {
    return cellPathPrefix.relativize(cellResolver.getCellPath(name).get());
  }

  @Override
  public void close() throws IOException {
    executionStrategy.close();
  }

  @Override
  public void build(
      ListeningExecutorService service, BuildRule rule, BuildExecutorRunner executorRunner) {
    Preconditions.checkState(rule instanceof ModernBuildRule);
    service.execute(
        () ->
            executorRunner.runWithExecutor(
                (executionContext, buildRuleBuildContext, buildableContext, stepRunner) -> {
                  executeRule(rule, executionContext, buildRuleBuildContext, buildableContext);
                }));
  }

  private void executeRule(
      BuildRule rule,
      ExecutionContext executionContext,
      BuildContext buildRuleBuildContext,
      BuildableContext buildableContext)
      throws IOException, StepFailedException, InterruptedException {
    Set<Path> outputs;
    HashCode hash;
    FileTreeBuilder inputsBuilder;
    ModernBuildRule<?> converted;

    try (Scope ignored = LeafEvents.scope(executionContext.getBuckEventBus(), "serializing")) {
      converted = (ModernBuildRule<?>) rule;
      Buildable original = converted.getBuildable();
      hash = serializer.serialize(new BuildableAndTarget(original, rule.getBuildTarget()));
    }

    try (Scope ignored =
        LeafEvents.scope(executionContext.getBuckEventBus(), "constructing_inputs_tree")) {
      inputsBuilder = new FileTreeBuilder();
      addBuckConfigInputs(inputsBuilder);
      addDeserializationInputs(hash, inputsBuilder);
      addRuleInputs(inputsBuilder, converted, buildRuleBuildContext);

      outputs = new HashSet<>();
      converted.recordOutputs(
          path ->
              outputs.add(cellPathPrefix.relativize(rule.getProjectFilesystem().resolve(path))));
    }

    executionStrategy.build(
        executionContext,
        inputsBuilder,
        outputs,
        cellPathPrefix.relativize(rootCell.getRoot()),
        hash,
        rule.getBuildTarget(),
        cellPathPrefix);

    converted.recordOutputs(buildableContext);
  }

  private void addBuckConfigInputs(FileTreeBuilder inputsBuilder) throws IOException {
    for (Optional<String> cell : cellNames) {
      Path configPath = getPrefixRelativeCellPath(cell).resolve(".buckconfig");
      inputsBuilder.addFile(
          configPath,
          () -> {
            byte[] data = Preconditions.checkNotNull(cellToConfig.get(cell));
            return new InputFile(
                Preconditions.checkNotNull(configHashes.get(cell)),
                data.length,
                false,
                () -> new ByteArrayInputStream(data));
          });
    }
  }

  private void addRuleInputs(
      FileTreeBuilder inputsBuilder, ModernBuildRule<?> converted, BuildContext buildContext)
      throws IOException {

    FileInputsAdder inputsAdder =
        new FileInputsAdder(
            inputsBuilder,
            cellPathPrefix,
            fileHasher,
            this::getDirectoryContents,
            this::getSymlinkTarget);
    for (SourcePath inputSourcePath : converted.computeInputs()) {
      Path resolved =
          buildContext.getSourcePathResolver().getAbsolutePath(inputSourcePath).normalize();
      inputsAdder.addInput(resolved);
    }
  }

  private Map<Path, Path> symlinkTargets = new ConcurrentHashMap<>();
  private Map<Path, Iterable<Path>> directoryContents = new ConcurrentHashMap<>();

  @Nullable
  private Path getSymlinkTarget(Path path) throws IOException {
    try {
      Preconditions.checkState(path.startsWith(cellPathPrefix));
      return symlinkTargets.computeIfAbsent(
          path,
          ignored -> {
            try {
              if (!Files.isSymbolicLink(path)) {
                return null;
              }

              return Files.readSymbolicLink(path);
            } catch (IOException e) {
              throw new WrappedIOException(e);
            }
          });
    } catch (WrappedIOException e) {
      throw e.getCause();
    }
  }

  @Nullable
  private Iterable<Path> getDirectoryContents(Path path) throws IOException {
    try {
      Preconditions.checkState(path.startsWith(cellPathPrefix));
      return directoryContents.computeIfAbsent(
          path,
          ignored -> {
            if (!Files.isDirectory(path)) {
              return null;
            }
            try (Stream<Path> list = Files.list(path)) {
              return list.collect(Collectors.toList());
            } catch (IOException e) {
              throw new WrappedIOException(e);
            }
          });
    } catch (WrappedIOException e) {
      throw e.getCause();
    }
  }

  private static class WrappedIOException extends RuntimeException {
    private WrappedIOException(IOException cause) {
      super(cause);
    }

    @Override
    public synchronized IOException getCause() {
      return (IOException) super.getCause();
    }
  }

  private void addDeserializationInputs(HashCode hash, FileTreeBuilder inputsBuilder)
      throws IOException {
    class DataAdder {
      void addData(Path root, String hash, Node node) throws IOException {
        inputsBuilder.addFile(
            root.resolve("__value__"),
            () ->
                new InputFile(
                    hash, node.data.length, false, () -> new ByteArrayInputStream(node.data)));
        for (Map.Entry<String, Node> child : node.children.entrySet()) {
          addData(root.resolve(child.getKey()), child.getKey(), child.getValue());
        }
      }
    }

    new DataAdder()
        .addData(
            Paths.get("__data__").resolve(hash.toString()),
            hash.toString(),
            Preconditions.checkNotNull(nodeMap.get(hash)));
  }

  private static class Node {
    private final byte[] data;
    private final ImmutableSortedMap<String, Node> children;

    Node(byte[] data, ImmutableSortedMap<String, Node> children) {
      this.data = data;
      this.children = children;
    }
  }

  private static byte[] serializeConfig(BuckConfig config) {
    StringBuilder builder = new StringBuilder();
    config
        .getConfig()
        .getSectionToEntries()
        .forEach(
            (key, value) -> {
              builder.append(String.format("[%s]\n", key));
              value.forEach(
                  (key1, value1) -> builder.append(String.format("  %s=%s\n", key1, value1)));
            });
    return builder.toString().getBytes(Charsets.UTF_8);
  }
}
