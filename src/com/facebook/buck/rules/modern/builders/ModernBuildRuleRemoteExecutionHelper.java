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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.exceptions.WrapsException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.io.file.GlobPatternMatcher;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.jvm.java.version.JavaVersion;
import com.facebook.buck.remoteexecution.UploadDataSupplier;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Directory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.DirectoryNode;
import com.facebook.buck.remoteexecution.interfaces.Protocol.FileNode;
import com.facebook.buck.remoteexecution.interfaces.Protocol.SymlinkNode;
import com.facebook.buck.remoteexecution.proto.WorkerRequirements;
import com.facebook.buck.remoteexecution.util.MerkleTreeNodeCache;
import com.facebook.buck.remoteexecution.util.MerkleTreeNodeCache.MerkleTreeNode;
import com.facebook.buck.remoteexecution.util.MerkleTreeNodeCache.NodeData;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.Serializer;
import com.facebook.buck.rules.modern.Serializer.Delegate;
import com.facebook.buck.rules.modern.impl.InputsMapBuilder;
import com.facebook.buck.rules.modern.impl.InputsMapBuilder.Data;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ConsoleParams;
import com.facebook.buck.util.Memoizer;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.env.BuckClasspath;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.base.Joiner;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
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
public class ModernBuildRuleRemoteExecutionHelper implements RemoteExecutionHelper {

  private static final Logger LOG = Logger.get(ModernBuildRuleRemoteExecutionHelper.class);

  private static final Path TRAMPOLINE =
      Paths.get(
          System.getProperty(
              "buck.path_to_isolated_trampoline",
              "src/com/facebook/buck/rules/modern/builders/trampoline.sh"));

  private static final String PLUGIN_RESOURCES = System.getProperty("buck.module.resources");
  private static final String PLUGIN_ROOT = System.getProperty("pf4j.pluginsDir");
  // necessary for isolated buck to work correctly
  private static final String BASE_BUCK_OUT_DIR = BuckConstant.getBuckOutputPath().toString();
  private static final Path TRAMPOLINE_PATH = Paths.get("__trampoline__.sh");
  public static final Path METADATA_PATH = Paths.get(".buck.metadata");
  private static final String FILE_HASH_VERIFICATION = "hash.verify";

  private static final String BUCK_CONFIG_EXPERIMENTS_SECTION_KEY = "experiments";
  private static final String BUCK_CONFIG_FILTER_FIELDS_FOR_RE_FLAG_NAME =
      "filter_buck_config_fields_for_re";

  private final InputsMapBuilder inputsMapBuilder;
  private final ImmutableSet<GlobPatternMatcher> ignorePaths;

  /** Gets the shared path prefix of all the cells. */
  private static AbsPath getCellPathPrefix(
      CellPathResolver cellResolver, ImmutableSet<CanonicalCellName> cellNames) {
    return AbsPath.of(
        MorePaths.splitOnCommonPrefix(
                cellNames.stream()
                    .map(name -> cellResolver.getNewCellPathResolver().getCellPath(name).getPath())
                    .collect(ImmutableList.toImmutableList()))
            .get()
            .getFirst());
  }

  /** Gets all the canonical cell names. */
  private static ImmutableSet<CanonicalCellName> getCellNames(Cell rootCell) {
    return rootCell.getCellProvider().getAllCells().stream()
        .map(Cell::getCanonicalName)
        .collect(ImmutableSet.toImmutableSet());
  }

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

    RequiredFile(RelPath path, FileNode fileNode, UploadDataSupplier dataSupplier) {
      this(path.getPath(), fileNode, dataSupplier);
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

  private final ThrowingSupplier<ImmutableList<RequiredFile>, IOException> sharedRequiredFiles;
  private final ThrowingSupplier<MerkleTreeNode, IOException> sharedFilesNode;

  private final MerkleTreeNodeCache nodeCache;

  private final BuckEventBus eventBus;

  private final SourcePathResolverAdapter pathResolver;
  private final CellPathResolver cellResolver;
  private final FileHashLoader fileHasher;
  private final Serializer serializer;
  private final Map<Class<?>, Map<String, Boolean>> loggedMessagesByClass =
      new ConcurrentHashMap<>();
  private final AbsPath cellPathPrefix;
  private final RelPath projectRoot;
  private final Map<HashCode, Node> nodeMap = new ConcurrentHashMap<>();
  private final HashFunction hasher;

  private final Protocol protocol;
  private final Memoizer<Digest> emptyDirectoryDigestMemoizer = new Memoizer<>();
  private final ConsoleParams consoleParams;

  public ModernBuildRuleRemoteExecutionHelper(
      BuckEventBus eventBus,
      Protocol protocol,
      SourcePathRuleFinder ruleFinder,
      Cell rootCell,
      FileHashLoader fileHasher,
      ImmutableSet<GlobPatternMatcher> ignorePaths,
      ConsoleParams consoleParams,
      boolean sanitizeBuckConfig) {
    this.consoleParams = consoleParams;
    this.ignorePaths = ignorePaths;
    ImmutableSet<CanonicalCellName> cellNames = getCellNames(rootCell);
    this.cellResolver = rootCell.getCellPathResolver();
    this.cellPathPrefix = getCellPathPrefix(cellResolver, cellNames);

    this.eventBus = eventBus;
    this.protocol = protocol;

    this.pathResolver = ruleFinder.getSourcePathResolver();
    this.projectRoot = cellPathPrefix.relativize(rootCell.getRoot().getPath());

    this.hasher = protocol.getHashFunction();
    this.fileHasher = fileHasher;

    Delegate delegate =
        (instance, data, children) -> {
          HashCode hash = hasher.hashBytes(data);
          Node node =
              new Node(
                  instance,
                  data,
                  hash,
                  children.stream()
                      .map(nodeMap::get)
                      .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
          nodeMap.put(hash, node);
          return hash;
        };
    this.serializer = new Serializer(ruleFinder, cellResolver, delegate);

    this.nodeCache = new MerkleTreeNodeCache(protocol);

    this.classPath = prepareClassPath(BuckClasspath::getClasspath);
    this.bootstrapClassPath = prepareClassPath(BuckClasspath::getBootstrapClasspath);
    this.trampoline =
        MoreSuppliers.memoize(
            () -> {
              Digest digest = protocol.computeDigest(Files.readAllBytes(TRAMPOLINE));
              return new RequiredFile(
                  TRAMPOLINE_PATH,
                  protocol.newFileNode(digest, TRAMPOLINE_PATH.getFileName().toString(), true),
                  new UploadDataSupplier() {
                    @Override
                    public Digest getDigest() {
                      return digest;
                    }

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
                  });
            },
            IOException.class);
    if (PLUGIN_RESOURCES == null || PLUGIN_ROOT == null) {
      pluginFiles = () -> new ClassPath(ImmutableList.of(), ImmutableList.of());
    } else {
      pluginFiles = prepareClassPath(ModernBuildRuleRemoteExecutionHelper::findPlugins);
    }

    this.inputsMapBuilder = new InputsMapBuilder();

    this.configFiles =
        MoreSuppliers.memoize(
            () -> {
              ImmutableList.Builder<RequiredFile> filesBuilder = ImmutableList.builder();
              for (CanonicalCellName cellName : cellNames) {
                RelPath configPath = getPrefixRelativeCellPath(cellName).resolveRel(".buckconfig");
                BuckConfig buckConfig =
                    rootCell.getCellProvider().getCellByCanonicalCellName(cellName).getBuckConfig();
                byte[] bytes = serializeConfig(buckConfig, sanitizeBuckConfig);
                Digest digest = protocol.computeDigest(bytes);
                filesBuilder.add(
                    new RequiredFile(
                        configPath,
                        protocol.newFileNode(
                            digest, configPath.getPath().getFileName().toString(), false),
                        new UploadDataSupplier() {
                          @Override
                          public Digest getDigest() {
                            return digest;
                          }

                          @Override
                          public InputStream get() {
                            return new ByteArrayInputStream(bytes);
                          }

                          @Override
                          public String describe() {
                            return String.format(
                                "Serialized .buckconfig (cell: %s size:%s).",
                                cellName.getLegacyName().orElse("root"), bytes.length);
                          }
                        }));
              }
              return filesBuilder.build();
            },
            IOException.class);

    this.sharedRequiredFiles =
        MoreSuppliers.memoize(
            () -> {
              ImmutableList.Builder<RequiredFile> requiredFiles = ImmutableList.builder();
              requiredFiles.addAll(classPath.get().requiredFiles);
              requiredFiles.addAll(bootstrapClassPath.get().requiredFiles);
              requiredFiles.addAll(pluginFiles.get().requiredFiles);
              requiredFiles.add(trampoline.get());
              requiredFiles.addAll(configFiles.get());
              return requiredFiles.build();
            },
            IOException.class);

    this.sharedFilesNode =
        MoreSuppliers.memoize(
            () -> {
              Map<Path, FileNode> sharedRequiredFiles = new HashMap<>();
              this.sharedRequiredFiles
                  .get()
                  .forEach(file -> sharedRequiredFiles.put(file.path, file.fileNode));
              return nodeCache.createNode(
                  sharedRequiredFiles, ImmutableMap.of(), ImmutableMap.of());
            },
            IOException.class);
  }

  @Override
  public Path getCellPathPrefix() {
    return cellPathPrefix.getPath();
  }

  @Override
  public boolean supportsRemoteExecution(ModernBuildRule<?> rule) {
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

  @Override
  public RemoteExecutionActionInfo prepareRemoteExecution(
      ModernBuildRule<?> rule,
      BiPredicate<Digest, String> requiredDataPredicate,
      WorkerRequirements workerRequirements)
      throws IOException {
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

    ImmutableList.Builder<UploadDataSupplier> requiredDataBuilder = ImmutableList.builder();

    try (Scope ignored = LeafEvents.scope(eventBus, "constructing_inputs_tree")) {
      getSharedFilesData(requiredDataPredicate).forEach(requiredDataBuilder::add);

      allNodes.add(
          getSerializationTreeAndInputs(hash, requiredDataPredicate, requiredDataBuilder::add));

      MerkleTreeNode inputsMerkleTree = resolveInputs(inputsMapBuilder.getInputs(rule));

      allNodes.add(inputsMerkleTree);
      getFileInputs(inputsMerkleTree, requiredDataPredicate, requiredDataBuilder::add);

      outputs = new HashSet<>();
      rule.recordOutputs(
          path ->
              outputs.add(
                  cellPathPrefix.relativize(rule.getProjectFilesystem().resolve(path)).getPath()));

      // The Buck client expects the METADATA_PATH to be written at the root of the cell of the rule
      // being built. METADATA_PATH is a relative path within the root of the cell; here we must
      // explicitly relativize it with the root of the cell.
      outputs.add(
          cellPathPrefix.relativize(rule.getProjectFilesystem().resolve(METADATA_PATH)).getPath());
    }

    try (Scope ignored = LeafEvents.scope(eventBus, "constructing_action_info")) {
      ImmutableList<String> command =
          getBuilderCommand(projectRoot, hash.toString(), consoleParams);
      ImmutableSortedMap<String, String> commandEnvironment =
          getBuilderEnvironmentOverrides(
              isolatedBootstrapClasspath, isolatedClasspath, cellPathPrefix);

      Protocol.Command actionCommand =
          protocol.newCommand(command, commandEnvironment, outputs, workerRequirements);

      MerkleTreeNode mergedMerkleTree = nodeCache.mergeNodes(allNodes);

      nodeCache.forAllData(
          mergedMerkleTree,
          childData -> {
            if (requiredDataPredicate.test(
                childData.getDigest(), childData.getDirectory().toString())) {
              requiredDataBuilder.add(
                  UploadDataSupplier.of(
                      childData.getDirectory().toString(),
                      childData.getDigest(),
                      () ->
                          new ByteArrayInputStream(
                              protocol.toByteArray(childData.getDirectory()))));
            }
          });

      NodeData data = nodeCache.getData(mergedMerkleTree);
      Digest inputsRootDigest = data.getDigest();

      byte[] commandData = protocol.toByteArray(actionCommand);
      Digest commandDigest = protocol.computeDigest(commandData);
      requiredDataBuilder.add(
          UploadDataSupplier.of(
              "command", commandDigest, () -> new ByteArrayInputStream(commandData)));

      Protocol.Action action = protocol.newAction(commandDigest, inputsRootDigest);
      byte[] actionData = protocol.toByteArray(action);
      Digest actionDigest = protocol.computeDigest(actionData);
      requiredDataBuilder.add(
          UploadDataSupplier.of(
              "action", actionDigest, () -> new ByteArrayInputStream(actionData)));

      return RemoteExecutionActionInfo.of(
          actionDigest, requiredDataBuilder.build(), data.getTotalSize(), outputs);
    }
  }

  private void getFileInputs(
      MerkleTreeNode inputsMerkleTree,
      BiPredicate<Digest, String> requiredDataPredicate,
      Consumer<UploadDataSupplier> dataConsumer) {
    inputsMerkleTree.forAllFiles(
        (path, fileNode) -> {
          if (requiredDataPredicate.test(fileNode.getDigest(), path.toString())) {
            dataConsumer.accept(
                new UploadDataSupplier() {
                  @Override
                  public Digest getDigest() {
                    return fileNode.getDigest();
                  }

                  @Override
                  public InputStream get() throws IOException {
                    return new FileInputStream(cellPathPrefix.resolve(path).toFile());
                  }

                  @Override
                  public String describe() {
                    try {
                      HashCode hash = hasher.hashBytes(ByteStreams.toByteArray(get()));
                      String description =
                          String.format(
                              "File (path:%s size:%s). Expected hash: [%s], Calculated hash: [%s]. Cached hash: [%s].",
                              path,
                              Files.size(cellPathPrefix.resolve(path).getPath()),
                              getDigest().getHash(),
                              hash.toString(),
                              fileHasher.get(cellPathPrefix.resolve(path)).toString());
                      if (cellPathPrefix.resolve(path).getParent() != null) {
                        AbsPath metaInfo =
                            cellPathPrefix
                                .resolve(path)
                                .getParent()
                                .resolve(
                                    path.getFileName().toString() + "." + FILE_HASH_VERIFICATION);
                        if (Files.exists(metaInfo.getPath())) {
                          description +=
                              String.format(
                                  " Meta Data Found: [%s]",
                                  String.join(",", Files.readAllLines(metaInfo.getPath())));
                        }
                      }
                      return description;
                    } catch (IOException e) {
                      LOG.warn(e, "Unable to describe file: " + path);
                      return String.format(
                          "failed to describe (path:%s error:%s)", path, e.getMessage());
                    }
                  }
                });
          }
        });
  }

  private Stream<UploadDataSupplier> getSharedFilesData(
      BiPredicate<Digest, String> requiredDataPredicate) throws IOException {
    ImmutableList<RequiredFile> requiredFiles = sharedRequiredFiles.get();
    return requiredFiles.stream()
        .map(requiredFile -> requiredFile.dataSupplier)
        .filter(
            dataSupplier ->
                requiredDataPredicate.test(dataSupplier.getDigest(), dataSupplier.describe()));
  }

  private final ConcurrentHashMap<Data, MerkleTreeNode> resolvedInputsCache =
      new ConcurrentHashMap<>();

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
            Map<Path, FileNode> files = new HashMap<>();
            Map<Path, DirectoryNode> emptyDirectories = new HashMap<>();
            Map<Path, SymlinkNode> symlinks = new HashMap<>();

            FileInputsAdder inputsAdder =
                new FileInputsAdder(
                    new FileInputsAdder.AbstractDelegate() {
                      @Override
                      public void addFile(AbsPath path) throws IOException {
                        for (GlobPatternMatcher matcher : ignorePaths) {
                          if (matcher.matches(path.getPath())) {
                            LOG.info("Ignoring input: " + path);
                            return;
                          }
                        }
                        files.put(
                            cellPathPrefix.relativize(path).getPath(),
                            protocol.newFileNode(
                                protocol.newDigest(
                                    fileHasher.get(path).toString(),
                                    (int) Files.size(path.getPath())),
                                path.getFileName().toString(),
                                Files.isExecutable(path.getPath())));
                      }

                      @Override
                      public void addEmptyDirectory(AbsPath path) {
                        DirectoryNode directoryNode =
                            protocol.newDirectoryNode(
                                path.getFileName().toString(), getEmptyDirectoryDigest());
                        emptyDirectories.put(
                            cellPathPrefix.relativize(path).getPath(), directoryNode);
                      }

                      @Override
                      public void addSymlink(AbsPath path, Path fixedTarget) {
                        symlinks.put(
                            cellPathPrefix.relativize(path).getPath(),
                            protocol.newSymlinkNode(path.getFileName().toString(), fixedTarget));
                      }
                    },
                    cellPathPrefix);

            for (SourcePath path : inputs.getPaths()) {
              inputsAdder.addInput(pathResolver.getAbsolutePath(path));
            }

            List<MerkleTreeNode> nodes = new ArrayList<>();
            nodes.add(nodeCache.createNode(files, symlinks, emptyDirectories));

            inputs.getChildren().forEach(child -> nodes.add(resolveInputs(child)));
            return nodeCache.mergeNodes(nodes);
          } catch (IOException e) {
            throw new BuckUncheckedExecutionException(e);
          }
        });
  }

  private Digest getEmptyDirectoryDigest() {
    return emptyDirectoryDigestMemoizer.get(
        () -> {
          Directory emptyDirectory =
              protocol.newDirectory(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
          return protocol.computeDigest(emptyDirectory);
        });
  }

  private static ImmutableList<Path> findPlugins() throws IOException {
    ImmutableList.Builder<Path> pathsBuilder = ImmutableList.builder();
    try (Stream<Path> files = Files.walk(Paths.get(PLUGIN_ROOT))) {
      files.filter(Files::isRegularFile).forEach(pathsBuilder::add);
    }
    try (Stream<Path> files = Files.walk(Paths.get(PLUGIN_RESOURCES))) {
      files.filter(Files::isRegularFile).forEach(pathsBuilder::add);
    }
    return pathsBuilder.build();
  }

  private RelPath getPrefixRelativeCellPath(CanonicalCellName name) {
    return cellPathPrefix.relativize(cellResolver.getNewCellPathResolver().getCellPath(name));
  }

  private static byte[] serializeConfig(BuckConfig config, boolean sanitizeBuckConfig) {
    StringBuilder builder = new StringBuilder();
    getBuckConfigFieldsForSerialization(config, sanitizeBuckConfig)
        .forEach(
            (key, value) -> {
              builder.append(String.format("[%s]\n", key));
              value.forEach(
                  (key1, value1) -> builder.append(String.format("  %s=%s\n", key1, value1)));
            });
    return builder.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static ImmutableMap<String, ImmutableMap<String, String>>
      getBuckConfigFieldsForSerialization(BuckConfig config, boolean sanitizeBuckConfig) {
    // TODO: remove the GK flag
    if (sanitizeBuckConfig
        || config.getBooleanValue(
            BUCK_CONFIG_EXPERIMENTS_SECTION_KEY,
            BUCK_CONFIG_FILTER_FIELDS_FOR_RE_FLAG_NAME,
            false)) {
      return config.prepareConfigForRE();
    }

    return config.getConfig().getSectionToEntries();
  }

  private String relativizePathString(AbsPath prefixRoot, String s) {
    if (s == null || s.length() == 0) {
      return "";
    }

    Path path = Paths.get(s);
    return path.isAbsolute() ? prefixRoot.relativize(path).toString() : path.toString();
  }

  private ImmutableSortedMap<String, String> getBuilderEnvironmentOverrides(
      ImmutableList<Path> bootstrapClasspath, Iterable<Path> classpath, AbsPath cellPrefixRoot) {

    // TODO(shivanker): Pass all user environment overrides to remote workers.
    String relativePluginRoot = relativizePathString(cellPrefixRoot, PLUGIN_ROOT);
    String relativePluginResources = relativizePathString(cellPrefixRoot, PLUGIN_RESOURCES);
    return ImmutableSortedMap.<String, String>naturalOrder()
        .put("CLASSPATH", classpathArg(bootstrapClasspath))
        .put("BUCK_CLASSPATH", classpathArg(classpath))
        .put(
            "EXTRA_JAVA_ARGS",
            ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(ModernBuildRuleRemoteExecutionHelper::isValidJVMArgument)
                .collect(Collectors.joining(" ")))
        .put("BUCK_JAVA_VERSION", String.valueOf(JavaVersion.getMajorVersion()))
        .put("BUCK_PLUGIN_ROOT", relativePluginRoot)
        .put("BASE_BUCK_OUT_DIR", BASE_BUCK_OUT_DIR)
        .put("BUCK_PLUGIN_RESOURCES", relativePluginResources)
        // TODO(cjhopman): This shouldn't be done here, it's not a Buck thing.
        .put("BUCK_DISTCC", "0")
        .build();
  }

  private static boolean isValidJVMArgument(String arg) {
    return arg.startsWith("--illegal-access")
        || arg.startsWith("--add-opens")
        || arg.startsWith("--add-exports");
  }

  private static ImmutableList<String> getBuilderCommand(
      RelPath projectRoot, String hash, ConsoleParams consoleParams) {
    String rootString = projectRoot.toString();
    if (rootString.isEmpty()) {
      rootString = "./";
    }
    return ImmutableList.of(
        "./" + TRAMPOLINE_PATH.toString(),
        rootString,
        hash,
        METADATA_PATH.toString(),
        consoleParams.isAnsiEscapeSequencesEnabled(),
        consoleParams.getVerbosity());
  }

  private static class Node implements Comparable<Node> {

    private final AddsToRuleKey instance;
    private final String hash;
    private final ImmutableSortedSet<Node> children;
    private final int dataLength;

    // We hold the serialized form of the instance in memory only until it is uploaded once. There
    // might be multiple different rules that reference this that all get scheduled at the same time
    // and so there's some races in uploading such that all of the rules might need to get a
    // reference to the serialized data but then only one will actually be uploaded. To handle this,
    // we hold a strong ref to the data until it is first requested, then we hold a WeakReference to
    // it. This means that if multiple concurrent rules request it, we'll still have the weak
    // reference around to give them.
    // There are a few edge cases where we will still need the data after that point: (1) if the
    // first upload fails (2) if our blob uploader becomes aware of TTLs such that for very long
    // builds it may upload the same thing multiple times. To support this, we reserialize the
    // instance if its requested when we no longer hold any reference to the original serialized
    // value.
    @Nullable private volatile byte[] data;

    private WeakReference<byte[]> dataRef;

    Node(
        AddsToRuleKey instance,
        @Nullable byte[] data,
        HashCode hash,
        ImmutableSortedSet<Node> children) {
      this.instance = instance;
      this.data = data;
      this.dataRef = new WeakReference<>(data);
      this.dataLength = data.length;
      this.hash = hash.toString();
      this.children = children;
    }

    public byte[] acquireData(Serializer serializer, HashFunction hasher) throws IOException {
      // We should only ever need the data once.
      byte[] current = dataRef.get();
      if (current != null) {
        dropData();
        return current;
      }

      byte[] reserialized = serializer.reserialize(instance);
      HashCode recomputedHash = hasher.hashBytes(reserialized);
      Verify.verify(recomputedHash.toString().equals(hash));
      this.dataRef = new WeakReference<>(reserialized);
      return reserialized;
    }

    @Override
    public int compareTo(Node other) {
      return hash.compareTo(other.hash);
    }

    public void dropData() {
      this.data = null;
    }
  }

  private MerkleTreeNode getSerializationTreeAndInputs(
      HashCode hash,
      BiPredicate<Digest, String> requiredDataPredicate,
      Consumer<UploadDataSupplier> dataBuilder)
      throws IOException {
    Map<Path, FileNode> fileNodes = new HashMap<>();
    class DataAdder {

      void addData(Path root, Node node) throws IOException {
        String fileName = "__value__";
        Path valuePath = root.resolve(node.hash).resolve(fileName);
        Digest digest = protocol.newDigest(node.hash, node.dataLength);
        fileNodes.put(valuePath, protocol.newFileNode(digest, fileName, false));
        if (!requiredDataPredicate.test(digest, node.instance.getClass().getName())) {
          node.dropData();
        } else {
          byte[] data = node.acquireData(serializer, hasher);
          dataBuilder.accept(
              new UploadDataSupplier() {
                @Override
                public InputStream get() {
                  return new ByteArrayInputStream(data);
                }

                @Override
                public Digest getDigest() {
                  return digest;
                }

                @Override
                public String describe() {
                  return String.format(
                      "Serialized java object (class:%s, size:%s).",
                      node.instance.getClass().getName(), node.dataLength);
                }
              });
        }

        for (Node value : node.children) {
          addData(root, value);
        }
      }
    }

    new DataAdder().addData(Paths.get("__data__"), Objects.requireNonNull(nodeMap.get(hash)));

    return nodeCache.createNode(fileNodes, ImmutableMap.of(), ImmutableMap.of());
  }

  private ThrowingSupplier<ClassPath, IOException> prepareClassPath(
      ThrowingSupplier<ImmutableList<Path>, IOException> classpath) {
    return MoreSuppliers.memoize(
        () -> {
          ImmutableList.Builder<Path> pathsBuilder = ImmutableList.builder();
          ImmutableList.Builder<RequiredFile> filesBuilder = ImmutableList.builder();

          for (Path path : classpath.get()) {
            if (path.startsWith(cellPathPrefix.getPath())) {
              RelPath relative = cellPathPrefix.relativize(path);
              pathsBuilder.add(relative.getPath());
              byte[] data = Files.readAllBytes(path);
              Digest digest = protocol.computeDigest(data);
              filesBuilder.add(
                  new RequiredFile(
                      relative,
                      protocol.newFileNode(digest, path.getFileName().toString(), false),
                      UploadDataSupplier.of(
                          path.getFileName().toString(),
                          digest,
                          () -> new FileInputStream(path.toFile()))));
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
