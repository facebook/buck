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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.Protocol.Digest;
import com.facebook.buck.remoteexecution.Protocol.FileNode;
import com.facebook.buck.remoteexecution.util.FileTreeBuilder;
import com.facebook.buck.remoteexecution.util.FileTreeBuilder.InputFile;
import com.facebook.buck.remoteexecution.util.FileTreeBuilder.ProtocolTreeBuilder;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.Serializer;
import com.facebook.buck.rules.modern.Serializer.Delegate;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.env.BuckClasspath;
import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * ModernBuildRuleRemoteExecutionHelper is used to create remote execution actions for a {@link
 * ModernBuildRule}.
 *
 * <p>To create the remote action, we serialize the MBR (in a graph of serialized {@link
 * AddsToRuleKey} parts such that different rules will share serialization if they share
 * references). We then send all the cells BuckConfigs in a serialized form, all of Buck's classpath
 * (including plugin classpath) and run the action remotely with the {@link
 * OutOfProcessIsolatedBuilder} (via trampoline.sh).
 */
public class ModernBuildRuleRemoteExecutionHelper {

  private static final Path TRAMPOLINE =
      Paths.get(
          System.getProperty(
              "buck.path_to_isolated_trampoline",
              "src/com/facebook/buck/rules/modern/builders/trampoline.sh"));

  private static final String pluginResources = System.getProperty("buck.module.resources");
  private static final String pluginRoot = System.getProperty("pf4j.pluginsDir");
  public static final Path TRAMPOLINE_PATH = Paths.get("__trampoline__.sh");

  /**
   * Used to store information about the common files required by all rules (classpaths, plugin
   * files, configuration, etc).
   */
  private static class RequiredFile {
    private final Path path;
    private final InputFile inputFile;

    RequiredFile(
        Path path, FileNode fileNode, ThrowingSupplier<InputStream, IOException> dataSupplier) {
      this.path = path;
      this.inputFile =
          new InputFile(
              fileNode.getDigest().getHash(),
              fileNode.getDigest().getSize(),
              fileNode.getIsExecutable(),
              dataSupplier);
    }
  }

  private static class ClassPath {
    private final ImmutableList<RequiredFile> requiredFiles;
    private final ImmutableList<Path> classpath;

    ClassPath(ImmutableList<RequiredFile> requiredFiles, ImmutableList<Path> classpath) {
      this.requiredFiles = requiredFiles;
      this.classpath = classpath;
    }
  }

  private final ThrowingSupplier<RequiredFile, IOException> trampoline;

  // TODO(cjhopman): We need to figure out a way to only hash these files once-per-daemon, not
  // once-per-command.
  private final ThrowingSupplier<ClassPath, IOException> classPath;
  private final ThrowingSupplier<ClassPath, IOException> bootstrapClassPath;
  private final ThrowingSupplier<ClassPath, IOException> pluginFiles;
  private final ThrowingSupplier<ImmutableList<RequiredFile>, IOException> configFiles;

  private final BuckEventBus eventBus;

  private final SourcePathResolver pathResolver;
  private final CellPathResolver cellResolver;
  private final ThrowingFunction<Path, HashCode, IOException> fileHasher;
  private final Serializer serializer;
  private final Path cellPathPrefix;
  private final Path projectRoot;
  private final Map<HashCode, Node> nodeMap;
  private final HashFunction hasher;

  private final Protocol protocol;

  // These are used to cache the results of some expensive I/O operations.
  private Map<Path, Path> symlinkTargets = new ConcurrentHashMap<>();
  private Map<Path, Iterable<Path>> directoryContents = new ConcurrentHashMap<>();

  public ModernBuildRuleRemoteExecutionHelper(
      BuckEventBus eventBus,
      Protocol protocol,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ImmutableSet<Optional<String>> cellNames,
      Path cellPathPrefix,
      ThrowingFunction<Path, HashCode, IOException> fileHasher) {
    this.eventBus = eventBus;
    this.protocol = protocol;

    this.cellResolver = cellResolver;
    this.pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    this.cellPathPrefix = cellPathPrefix;
    this.projectRoot = cellPathPrefix.relativize(rootCell.getRoot());

    this.nodeMap = new ConcurrentHashMap<>();
    this.hasher = protocol.getHashFunction();
    this.fileHasher = fileHasher;

    Delegate delegate =
        (instance, data, children) -> {
          HashCode hash = hasher.hashBytes(data);
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

    this.classPath = prepareClassPath(BuckClasspath::getClasspath);
    this.bootstrapClassPath = prepareClassPath(BuckClasspath::getBootstrapClasspath);
    this.trampoline =
        MoreSuppliers.memoize(
            () ->
                new RequiredFile(
                    TRAMPOLINE_PATH,
                    protocol.newFileNode(
                        protocol.computeDigest(Files.readAllBytes(TRAMPOLINE)),
                        TRAMPOLINE_PATH.getFileName().toString(),
                        true),
                    () -> new FileInputStream(TRAMPOLINE.toFile())),
            IOException.class);
    if (pluginResources == null || pluginRoot == null) {
      pluginFiles = () -> new ClassPath(ImmutableList.of(), ImmutableList.of());
    } else {
      pluginFiles = prepareClassPath(() -> findPlugins());
    }

    this.configFiles =
        MoreSuppliers.memoize(
            () -> {
              ImmutableList.Builder<RequiredFile> filesBuilder = ImmutableList.builder();
              for (Optional<String> cellName : cellNames) {
                Path configPath = getPrefixRelativeCellPath(cellName).resolve(".buckconfig");
                byte[] bytes =
                    serializeConfig(
                        rootCell
                            .getCellProvider()
                            .getCellByPath(cellResolver.getCellPath(cellName).get())
                            .getBuckConfig());
                filesBuilder.add(
                    new RequiredFile(
                        configPath,
                        protocol.newFileNode(
                            protocol.computeDigest(bytes),
                            configPath.getFileName().toString(),
                            false),
                        () -> new ByteArrayInputStream(bytes)));
              }
              return filesBuilder.build();
            },
            IOException.class);
  }

  /**
   * Gets all the information needed to run the rule via Remote Execution (inputs merkle tree,
   * action and digest, outputs).
   */
  RemoteExecutionActionInfo prepareRemoteExecution(ModernBuildRule<?> rule) throws IOException {
    Set<Path> outputs;
    HashCode hash;
    FileTreeBuilder inputsBuilder = new FileTreeBuilder();

    try (Scope ignored = LeafEvents.scope(eventBus, "serializing")) {
      Buildable original = rule.getBuildable();
      hash = serializer.serialize(new BuildableAndTarget(original, rule.getBuildTarget()));
    }

    try (Scope ignored = LeafEvents.scope(eventBus, "constructing_inputs_tree")) {
      addSharedFilesData(inputsBuilder);

      addDeserializationInputs(hash, inputsBuilder);
      addRuleInputs(inputsBuilder, rule);

      outputs = new HashSet<>();
      rule.recordOutputs(
          path ->
              outputs.add(cellPathPrefix.relativize(rule.getProjectFilesystem().resolve(path))));
    }

    ImmutableList<Path> isolatedClasspath = classPath.get().classpath;
    ImmutableList<Path> isolatedBootstrapClasspath = bootstrapClassPath.get().classpath;

    ImmutableList<String> command = getBuilderCommand(projectRoot, hash.toString());
    ImmutableSortedMap<String, String> commandEnvironment =
        getBuilderEnvironmentOverrides(
            isolatedBootstrapClasspath, isolatedClasspath, cellPathPrefix);

    Protocol.Command actionCommand = protocol.newCommand(command, commandEnvironment, outputs);

    HashMap<Digest, ThrowingSupplier<InputStream, IOException>> requiredDataBuilder =
        new HashMap<>();
    ProtocolTreeBuilder grpcTreeBuilder =
        new ProtocolTreeBuilder(requiredDataBuilder::put, directory -> {}, protocol);
    Digest inputsRootDigest = inputsBuilder.buildTree(grpcTreeBuilder);
    byte[] commandData = protocol.toByteArray(actionCommand);
    Digest commandDigest = protocol.computeDigest(commandData);
    requiredDataBuilder.put(commandDigest, () -> new ByteArrayInputStream(commandData));

    Protocol.Action action = protocol.newAction(commandDigest, inputsRootDigest);
    byte[] actionData = protocol.toByteArray(action);
    Digest actionDigest = protocol.computeDigest(actionData);
    requiredDataBuilder.put(actionDigest, () -> new ByteArrayInputStream(actionData));

    return RemoteExecutionActionInfo.of(
        actionDigest, ImmutableMap.copyOf(requiredDataBuilder), outputs);
  }

  private void addSharedFilesData(FileTreeBuilder fileTreeBuilder) throws IOException {
    for (RequiredFile requiredFile : classPath.get().requiredFiles) {
      fileTreeBuilder.addFile(requiredFile.path, () -> requiredFile.inputFile);
    }
    for (RequiredFile requiredFile : bootstrapClassPath.get().requiredFiles) {
      fileTreeBuilder.addFile(requiredFile.path, () -> requiredFile.inputFile);
    }
    for (RequiredFile requiredFile : pluginFiles.get().requiredFiles) {
      fileTreeBuilder.addFile(requiredFile.path, () -> requiredFile.inputFile);
    }
    for (RequiredFile f : configFiles.get()) {
      fileTreeBuilder.addFile(f.path, () -> f.inputFile);
    }
    fileTreeBuilder.addFile(trampoline.get().path, () -> trampoline.get().inputFile);
  }

  private static ImmutableList<Path> findPlugins() throws IOException {
    ImmutableList.Builder<Path> pathsBuilder = ImmutableList.builder();
    try (Stream<Path> files = Files.walk(Paths.get(pluginRoot))) {
      files.filter(Files::isRegularFile).forEach(pathsBuilder::add);
    }
    try (Stream<Path> files = Files.walk(Paths.get(pluginResources))) {
      files.filter(Files::isRegularFile).forEach(pathsBuilder::add);
    }
    return pathsBuilder.build();
  }

  private Path getPrefixRelativeCellPath(Optional<String> name) {
    return cellPathPrefix.relativize(Optionals.require(cellResolver.getCellPath(name)));
  }

  private void addRuleInputs(FileTreeBuilder inputsBuilder, ModernBuildRule<?> converted)
      throws IOException {

    FileInputsAdder inputsAdder =
        new FileInputsAdder(
            new FileInputsAdder.Delegate() {
              @Override
              public void addFile(Path path) throws IOException {
                inputsBuilder.addFile(
                    cellPathPrefix.relativize(path),
                    () ->
                        new InputFile(
                            fileHasher.apply(path).toString(),
                            (int) Files.size(path),
                            Files.isExecutable(path),
                            () -> new FileInputStream(path.toFile())));
              }

              @Override
              public void addSymlink(Path symlink, Path fixedTarget) {
                inputsBuilder.addSymlink(cellPathPrefix.relativize(symlink), fixedTarget);
              }

              @Override
              public Iterable<Path> getDirectoryContents(Path target) throws IOException {
                return getCachedDirectoryContents(target);
              }

              @Override
              public Path getSymlinkTarget(Path path) throws IOException {
                return getCachedSymlinkTarget(path);
              }
            },
            cellPathPrefix);
    for (SourcePath inputSourcePath : converted.computeInputs()) {
      Path resolved = pathResolver.getAbsolutePath(inputSourcePath).normalize();
      inputsAdder.addInput(resolved);
    }
  }

  @Nullable
  private Path getCachedSymlinkTarget(Path path) throws IOException {
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
  private Iterable<Path> getCachedDirectoryContents(Path path) throws IOException {
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
            Objects.requireNonNull(nodeMap.get(hash)));
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

  private ImmutableSortedMap<String, String> getBuilderEnvironmentOverrides(
      ImmutableList<Path> bootstrapClasspath, Iterable<Path> classpath, Path cellPrefixRoot) {

    // TODO(shivanker): Pass all user environment overrides to remote workers.
    String relativePluginRoot = "";
    if (pluginRoot != null) {
      Path rootPath = Paths.get(pluginRoot);
      relativePluginRoot =
          (rootPath.isAbsolute() ? cellPrefixRoot.relativize(Paths.get(pluginRoot)) : pluginRoot)
              .toString();
    }
    String relativePluginResources =
        pluginResources == null
            ? ""
            : cellPrefixRoot.relativize(Paths.get(pluginResources)).toString();
    return ImmutableSortedMap.of(
        "CLASSPATH",
        classpathArg(bootstrapClasspath),
        "BUCK_CLASSPATH",
        classpathArg(classpath),
        "BUCK_PLUGIN_ROOT",
        relativePluginRoot,
        "BUCK_PLUGIN_RESOURCES",
        relativePluginResources,
        // TODO(cjhopman): This shouldn't be done here, it's not a Buck thing.
        "BUCK_DISTCC",
        "0");
  }

  private static ImmutableList<String> getBuilderCommand(Path projectRoot, String hash) {
    String rootString = projectRoot.toString();
    if (rootString.isEmpty()) {
      rootString = "./";
    }
    return ImmutableList.of("./" + TRAMPOLINE_PATH.toString(), rootString, hash);
  }

  private ThrowingSupplier<ClassPath, IOException> prepareClassPath(
      ThrowingSupplier<ImmutableList<Path>, IOException> classpath) {
    return MoreSuppliers.memoize(
        () -> {
          ImmutableList.Builder<Path> pathsBuilder = ImmutableList.builder();
          ImmutableList.Builder<RequiredFile> filesBuilder = ImmutableList.builder();

          for (Path path : classpath.get()) {
            if (path.startsWith(cellPathPrefix)) {
              Path relative = cellPathPrefix.relativize(path);
              pathsBuilder.add(relative);
              byte[] data = Files.readAllBytes(path);
              filesBuilder.add(
                  new RequiredFile(
                      relative,
                      protocol.newFileNode(
                          protocol.computeDigest(data), path.getFileName().toString(), false),
                      () -> new FileInputStream(path.toFile())));
            } else {
              pathsBuilder.add(path);
            }
          }
          return new ClassPath(filesBuilder.build(), pathsBuilder.build());
        },
        IOException.class);
  }

  private static String classpathArg(Iterable<Path> classpath) {
    return Joiner.on(File.pathSeparator).join(classpath);
  }
}
