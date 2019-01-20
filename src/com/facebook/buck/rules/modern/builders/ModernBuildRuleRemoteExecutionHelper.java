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
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.Protocol.Digest;
import com.facebook.buck.remoteexecution.Protocol.FileNode;
import com.facebook.buck.remoteexecution.Protocol.SymlinkNode;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.util.MerkleTreeNodeCache;
import com.facebook.buck.remoteexecution.util.MerkleTreeNodeCache.MerkleTreeNode;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.Serializer;
import com.facebook.buck.rules.modern.Serializer.Delegate;
import com.facebook.buck.rules.modern.impl.InputsMapBuilder;
import com.facebook.buck.rules.modern.impl.InputsMapBuilder.Data;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.env.BuckClasspath;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.exceptions.WrapsException;
import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

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
  private static final Logger LOG = Logger.get(ModernBuildRuleRemoteExecutionHelper.class);
  private static final Path TRAMPOLINE =
      Paths.get(
          System.getProperty(
              "buck.path_to_isolated_trampoline",
              "src/com/facebook/buck/rules/modern/builders/trampoline.sh"));

  private static final String pluginResources = System.getProperty("buck.module.resources");
  private static final String pluginRoot = System.getProperty("pf4j.pluginsDir");
  public static final Path TRAMPOLINE_PATH = Paths.get("__trampoline__.sh");

  private final InputsMapBuilder inputsMapBuilder;

  /**
   * Used to store information about the common files required by all rules (classpaths, plugin
   * files, configuration, etc).
   */
  private static class RequiredFile {
    private final Path path;
    private final FileNode fileNode;
    private final UploadDataSupplier dataSupplier;

    RequiredFile(Path path, FileNode fileNode, UploadDataSupplier dataSupplier) {
      this.path = path;
      this.fileNode = fileNode;
      this.dataSupplier = dataSupplier;
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

  private final ThrowingSupplier<MerkleTreeNode, IOException> sharedFilesNode;

  private final MerkleTreeNodeCache nodeCache;

  private final BuckEventBus eventBus;

  private final SourcePathResolver pathResolver;
  private final CellPathResolver cellResolver;
  private final ThrowingFunction<Path, HashCode, IOException> fileHasher;
  private final Serializer serializer;
  private final Map<Class<?>, Map<String, Boolean>> loggedMessagesByClass;
  private final Path cellPathPrefix;
  private final Path projectRoot;
  private final Map<HashCode, Node> nodeMap;
  private final HashFunction hasher;

  private final Protocol protocol;

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

    this.loggedMessagesByClass = new ConcurrentHashMap<>();

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

    this.nodeCache = new MerkleTreeNodeCache(protocol);

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
                    new UploadDataSupplier() {
                      @Override
                      public InputStream get() throws IOException {
                        return new FileInputStream(TRAMPOLINE.toFile());
                      }

                      @Override
                      public String describe() {
                        try {
                          return String.format(
                              "MBR trampoline (path: %s size:%s).",
                              TRAMPOLINE, Files.size(TRAMPOLINE));
                        } catch (IOException e) {
                          return String.format("failed to describe (%s)", e.getMessage());
                        }
                      }
                    }),
            IOException.class);
    if (pluginResources == null || pluginRoot == null) {
      pluginFiles = () -> new ClassPath(ImmutableList.of(), ImmutableList.of());
    } else {
      pluginFiles = prepareClassPath(() -> findPlugins());
    }

    this.inputsMapBuilder = new InputsMapBuilder();

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
                        new UploadDataSupplier() {
                          @Override
                          public InputStream get() {
                            return new ByteArrayInputStream(bytes);
                          }

                          @Override
                          public String describe() {
                            return String.format(
                                "Serialized .buckconfig (cell: %s size:%s).",
                                cellName.orElse("root"), bytes.length);
                          }
                        }));
              }
              return filesBuilder.build();
            },
            IOException.class);

    this.sharedFilesNode =
        MoreSuppliers.memoize(
            () -> {
              Map<Path, FileNode> sharedRequiredFiles = new HashMap<>();
              classPath
                  .get()
                  .requiredFiles
                  .forEach(file -> sharedRequiredFiles.put(file.path, file.fileNode));
              bootstrapClassPath
                  .get()
                  .requiredFiles
                  .forEach(file -> sharedRequiredFiles.put(file.path, file.fileNode));
              pluginFiles
                  .get()
                  .requiredFiles
                  .forEach(file -> sharedRequiredFiles.put(file.path, file.fileNode));
              sharedRequiredFiles.put(trampoline.get().path, trampoline.get().fileNode);
              configFiles.get().forEach(file -> sharedRequiredFiles.put(file.path, file.fileNode));
              return nodeCache.createNode(sharedRequiredFiles, ImmutableMap.of());
            },
            IOException.class);
  }

  boolean supportsRemoteExecution(ModernBuildRule<?> rule) {
    // TODO(cjhopman): We may want to extend this to support returning more information about what
    // is required from the RE system (i.e. toolchains/platforms/etc).
    try {
      // We don't use the result of serialization here, we're just verifying that it succeeds. The
      // serializer will memoize the results so we won't recompute it when we need it later.
      serializer.serialize(rule.getBuildable());
      return true;
    } catch (Exception e) {
      String message = WrapsException.getRootCause(e).getMessage();
      loggedMessagesByClass
          .computeIfAbsent(rule.getClass(), ignored -> new ConcurrentHashMap<>())
          .computeIfAbsent(
              message == null ? "" : message,
              ignored -> {
                LOG.warn(
                    e,
                    "Remote Execution not supported for instance of %s due to serialization failure.",
                    rule.getClass());
                return true;
              });

      return false;
    }
  }

  /**
   * Gets all the information needed to run the rule via Remote Execution (inputs merkle tree,
   * action and digest, outputs).
   */
  RemoteExecutionActionInfo prepareRemoteExecution(ModernBuildRule<?> rule) throws IOException {
    Set<Path> outputs;
    HashCode hash;

    try (Scope ignored = LeafEvents.scope(eventBus, "serializing")) {
      Buildable original = rule.getBuildable();
      hash = serializer.serialize(new BuildableAndTarget(original, rule.getBuildTarget()));
    }

    ImmutableList<Path> isolatedClasspath = classPath.get().classpath;
    ImmutableList<Path> isolatedBootstrapClasspath = bootstrapClassPath.get().classpath;

    List<MerkleTreeNode> allNodes = new ArrayList<>();
    allNodes.add(sharedFilesNode.get());

    Map<Digest, UploadDataSupplier> requiredDataBuilder = new HashMap<>();

    try (Scope ignored2 = LeafEvents.scope(eventBus, "constructing_inputs_tree")) {
      addSharedFilesData(requiredDataBuilder);

      allNodes.add(getSerializationTreeAndInputs(hash, requiredDataBuilder));

      MerkleTreeNode inputsMerkleTree = resolveInputs(inputsMapBuilder.getInputs(rule));

      allNodes.add(inputsMerkleTree);
      addFileInputs(inputsMerkleTree, requiredDataBuilder);

      outputs = new HashSet<>();
      rule.recordOutputs(
          path ->
              outputs.add(cellPathPrefix.relativize(rule.getProjectFilesystem().resolve(path))));
    }

    try (Scope ignored2 = LeafEvents.scope(eventBus, "constructing_action_info")) {
      ImmutableList<String> command = getBuilderCommand(projectRoot, hash.toString());
      ImmutableSortedMap<String, String> commandEnvironment =
          getBuilderEnvironmentOverrides(
              isolatedBootstrapClasspath, isolatedClasspath, cellPathPrefix);

      Protocol.Command actionCommand = protocol.newCommand(command, commandEnvironment, outputs);

      MerkleTreeNode mergedMerkleTree = nodeCache.mergeNodes(allNodes);

      nodeCache.forAllData(
          mergedMerkleTree,
          childData ->
              requiredDataBuilder.put(
                  childData.getDigest(),
                  () -> new ByteArrayInputStream(protocol.toByteArray(childData.getDirectory()))));

      Digest inputsRootDigest = nodeCache.getData(mergedMerkleTree).getDigest();

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
  }

  private void addFileInputs(
      MerkleTreeNode inputsMerkleTree, Map<Digest, UploadDataSupplier> requiredDataBuilder) {
    inputsMerkleTree.forAllFiles(
        cellPathPrefix,
        (path, fileNode) ->
            requiredDataBuilder.put(
                fileNode.getDigest(),
                new UploadDataSupplier() {
                  @Override
                  public InputStream get() throws IOException {
                    return new FileInputStream(path.toFile());
                  }

                  @Override
                  public String describe() {
                    try {
                      return String.format("File (path:%s size:%s)", path, Files.size(path));
                    } catch (IOException e) {
                      return String.format("failed to describe (%s)", e.getMessage());
                    }
                  }
                }));
  }

  private void addSharedFilesData(Map<Digest, UploadDataSupplier> requiredDataBuilder)
      throws IOException {
    for (RequiredFile requiredFile : classPath.get().requiredFiles) {
      requiredDataBuilder.put(requiredFile.fileNode.getDigest(), requiredFile.dataSupplier);
    }
    for (RequiredFile requiredFile : bootstrapClassPath.get().requiredFiles) {
      requiredDataBuilder.put(requiredFile.fileNode.getDigest(), requiredFile.dataSupplier);
    }
    for (RequiredFile requiredFile : pluginFiles.get().requiredFiles) {
      requiredDataBuilder.put(requiredFile.fileNode.getDigest(), requiredFile.dataSupplier);
    }
    for (RequiredFile f : configFiles.get()) {
      requiredDataBuilder.put(f.fileNode.getDigest(), f.dataSupplier);
    }
    requiredDataBuilder.put(trampoline.get().fileNode.getDigest(), trampoline.get().dataSupplier);
  }

  private ConcurrentHashMap<Data, MerkleTreeNode> resolvedInputsCache = new ConcurrentHashMap<>();

  private MerkleTreeNode resolveInputs(Data inputs) {
    MerkleTreeNode cached = resolvedInputsCache.get(inputs);
    if (cached != null) {
      return cached;
    }

    // Ensure the children are computed.
    inputs.getChildren().forEach(this::resolveInputs);

    return resolvedInputsCache.computeIfAbsent(
        inputs,
        ignored -> {
          try {
            HashMap<Path, FileNode> files = new HashMap<>();
            HashMap<Path, SymlinkNode> symlinks = new HashMap<>();

            FileInputsAdder inputsAdder =
                new FileInputsAdder(
                    new FileInputsAdder.AbstractDelegate() {
                      @Override
                      public void addFile(Path path) throws IOException {
                        files.put(
                            cellPathPrefix.relativize(path),
                            protocol.newFileNode(
                                protocol.newDigest(
                                    fileHasher.apply(path).toString(), (int) Files.size(path)),
                                path.getFileName().toString(),
                                Files.isExecutable(path)));
                      }

                      @Override
                      public void addSymlink(Path path, Path fixedTarget) {
                        symlinks.put(
                            cellPathPrefix.relativize(path),
                            protocol.newSymlinkNode(path.getFileName().toString(), fixedTarget));
                      }
                    },
                    cellPathPrefix);

            for (SourcePath path : inputs.getPaths()) {
              inputsAdder.addInput(pathResolver.getAbsolutePath(path));
            }

            List<MerkleTreeNode> nodes = new ArrayList<>();
            nodes.add(nodeCache.createNode(files, symlinks));

            inputs.getChildren().forEach(child -> nodes.add(resolveInputs(child)));
            return nodeCache.mergeNodes(nodes);
          } catch (IOException e) {
            throw new BuckUncheckedExecutionException(e);
          }
        });
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

  private static class Node {
    private final byte[] data;

    private final ImmutableSortedMap<String, Node> children;

    Node(byte[] data, ImmutableSortedMap<String, Node> children) {
      this.data = data;
      this.children = children;
    }
  }

  private MerkleTreeNode getSerializationTreeAndInputs(
      HashCode hash, Map<Digest, UploadDataSupplier> requiredDataBuilder) {
    Map<Path, FileNode> fileNodes = new HashMap<>();
    class DataAdder {
      void addData(Path root, String hash, Node node) {
        String fileName = "__value__";
        Path valuePath = root.resolve(fileName);
        Digest digest = protocol.newDigest(hash, node.data.length);
        fileNodes.put(valuePath, protocol.newFileNode(digest, fileName, false));
        requiredDataBuilder.put(
            digest,
            new UploadDataSupplier() {
              @Override
              public InputStream get() {
                return new ByteArrayInputStream(node.data);
              }

              @Override
              public String describe() {
                return String.format("Serialized java object (size:%s).", node.data.length);
              }
            });

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

    return nodeCache.createNode(fileNodes, ImmutableMap.of());
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
