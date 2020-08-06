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

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.cell.CellConfig;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.cell.impl.LocalCellProviderFactory;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.module.BuckModuleManager;
import com.facebook.buck.core.module.impl.BuckModuleJarHashProvider;
import com.facebook.buck.core.module.impl.DefaultBuckModuleManager;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleResolver;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.sourcepath.resolver.impl.AbstractSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.ToolchainProviderFactory;
import com.facebook.buck.core.toolchain.impl.DefaultToolchainProviderFactory;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.rules.modern.Deserializer;
import com.facebook.buck.rules.modern.Deserializer.DataProvider;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.CloseableWrapper;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;

/**
 * IsolatedBuildableBuilder is used to build rules in isolation. Users need to construct a working
 * directory with all the required inputs (including .buckconfig files for each cell). The data for
 * deserializing build rules is expected to be contained in the "__data__" directory under the root
 * (see getProvider() below for the expected layout of this directory).
 */
public abstract class IsolatedBuildableBuilder {
  private static final Logger LOG = Logger.get(IsolatedBuildableBuilder.class);
  private final BuildContext buildContext;
  private final ExecutionContext executionContext;
  private final Function<Optional<String>, ProjectFilesystem> filesystemFunction;
  private final Deserializer.ClassFinder classFinder;
  private final Path dataRoot;
  private final BuckEventBus eventBus;
  private final Function<Optional<String>, ToolchainProvider> toolchainProviderFunction;
  private final Path metadataPath;

  @SuppressWarnings("PMD.EmptyCatchBlock")
  IsolatedBuildableBuilder(Path workRoot, Path projectRoot, Path metadataPath) throws IOException {
    AbsPath canonicalWorkRoot = AbsPath.of(workRoot.toRealPath()).normalize();
    AbsPath canonicalProjectRoot = canonicalWorkRoot.resolve(projectRoot).normalize();
    this.metadataPath = metadataPath;
    this.dataRoot = workRoot.resolve("__data__");

    PluginManager pluginManager = BuckPluginManagerFactory.createPluginManager();

    this.classFinder =
        (name) -> {
          try {
            return Class.forName(name);
          } catch (ClassNotFoundException e) {
            for (PluginWrapper plugin : pluginManager.getPlugins()) {
              try {
                return plugin.getPluginClassLoader().loadClass(name);
              } catch (ClassNotFoundException e1) {
                // ignored
              }
            }
            throw new BuckUncheckedExecutionException(e, "When looking up class %s.", name);
          }
        };

    // Setup filesystemCell and buck config.
    Config config = Configs.createDefaultConfig(canonicalProjectRoot.getPath());
    ProjectFilesystemFactory projectFilesystemFactory = new DefaultProjectFilesystemFactory();

    // Root filesystemCell doesn't require embedded buck-out info.
    ProjectFilesystem filesystem =
        projectFilesystemFactory.createProjectFilesystem(
            CanonicalCellName.rootCell(),
            canonicalProjectRoot,
            config,
            BuckPaths.getBuckOutIncludeTargetConfigHashFromRootCellConfig(config));

    Architecture architecture = Architecture.detect();
    Platform platform = Platform.detect();

    ImmutableMap<String, String> clientEnvironment = EnvVariablesProvider.getSystemEnv();

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.create(filesystem.getRootPath(), config);
    UnconfiguredBuildTargetViewFactory buildTargetFactory =
        new ParsingUnconfiguredBuildTargetViewFactory();

    BuckConfig buckConfig =
        new BuckConfig(
            config,
            filesystem,
            architecture,
            platform,
            clientEnvironment,
            buildTargetName ->
                buildTargetFactory.create(buildTargetName, cellPathResolver.getCellNameResolver()));

    BuckModuleManager moduleManager =
        new DefaultBuckModuleManager(pluginManager, new BuckModuleJarHashProvider());

    Console console = createConsole();
    ProcessExecutor processExecutor = new DefaultProcessExecutor(console);
    ExecutableFinder executableFinder = new ExecutableFinder();

    ToolchainProviderFactory toolchainProviderFactory =
        new DefaultToolchainProviderFactory(
            pluginManager, clientEnvironment, processExecutor, executableFinder);

    CellProvider cellProvider =
        LocalCellProviderFactory.create(
            filesystem,
            buckConfig,
            CellConfig.EMPTY_INSTANCE,
            cellPathResolver.getPathMapping(),
            cellPathResolver,
            moduleManager,
            toolchainProviderFactory,
            projectFilesystemFactory,
            buildTargetFactory);

    this.filesystemFunction =
        (cellName) ->
            cellProvider
                .getCellByPath(cellPathResolver.getCellPath(cellName).get())
                .getFilesystem();

    JavaPackageFinder javaPackageFinder =
        buckConfig.getView(JavaBuckConfig.class).createDefaultJavaPackageFinder();

    this.eventBus = createEventBus(console);

    this.executionContext =
        ExecutionContext.builder()
            .setConsole(console)
            .setBuckEventBus(eventBus)
            .setPlatform(platform)
            .setEnvironment(clientEnvironment)
            .setJavaPackageFinder(javaPackageFinder)
            .setExecutors(ImmutableMap.of())
            .setCellPathResolver(cellPathResolver)
            .setCells(cellProvider.getRootCell())
            .setBuildCellRootPath(canonicalProjectRoot.getPath())
            .setProcessExecutor(processExecutor)
            .setProjectFilesystemFactory(projectFilesystemFactory)
            .build();

    this.buildContext =
        BuildContext.of(
            new SourcePathResolverAdapter(
                new AbstractSourcePathResolver() {
                  @Override
                  protected ProjectFilesystem getBuildTargetSourcePathFilesystem(
                      BuildTargetSourcePath sourcePath) {
                    Preconditions.checkState(sourcePath instanceof ExplicitBuildTargetSourcePath);
                    BuildTarget target = sourcePath.getTarget();
                    return filesystemFunction.apply(target.getCell().getLegacyName());
                  }

                  @Override
                  protected ImmutableSortedSet<SourcePath> resolveDefaultBuildTargetSourcePath(
                      DefaultBuildTargetSourcePath targetSourcePath) {
                    throw new IllegalStateException(
                        "Cannot resolve DefaultBuildTargetSourcePaths when running with an isolated strategy. "
                            + "These should have been resolved to the underlying ExplicitBuildTargetSourcePath already.");
                  }

                  @Override
                  public String getSourcePathName(BuildTarget target, SourcePath sourcePath) {
                    throw new IllegalStateException(
                        "Cannot resolve SourcePath names during build when running with an isolated strategy.");
                  }
                }),
            canonicalProjectRoot.getPath(),
            javaPackageFinder,
            eventBus,
            buckConfig.getView(BuildBuckConfig.class).getShouldDeleteTemporaries());

    this.toolchainProviderFunction =
        cellName ->
            cellProvider
                .getCellByPath(cellPathResolver.getCellPath(cellName).get())
                .getToolchainProvider();

    RichStream.from(cellPathResolver.getCellPathsByRootCellExternalName().keySet())
        .forEachThrowing(
            name -> {
              // Sadly, some things assume this exists and writes to it.
              ProjectFilesystem fs = filesystemFunction.apply(Optional.of(name));
              BuckPaths configuredPaths = fs.getBuckPaths();

              fs.mkdirs(configuredPaths.getTmpDir());
              fs.mkdirs(configuredPaths.getBuckOut());
              fs.deleteFileAtPathIfExists(configuredPaths.getProjectRootDir());
              fs.writeContentsToPath(
                  fs.getRootPath().toString(), configuredPaths.getProjectRootDir());

              if (!configuredPaths.getConfiguredBuckOut().equals(configuredPaths.getBuckOut())
                  && buckConfig.getView(BuildBuckConfig.class).getBuckOutCompatLink()
                  && Platform.detect() != Platform.WINDOWS) {
                BuckPaths unconfiguredPaths =
                    configuredPaths.withConfiguredBuckOut(configuredPaths.getBuckOut());
                ImmutableMap<Path, Path> paths =
                    ImmutableMap.of(
                        unconfiguredPaths.getGenDir(), configuredPaths.getGenDir(),
                        unconfiguredPaths.getScratchDir(), configuredPaths.getScratchDir());
                for (Map.Entry<Path, Path> entry : paths.entrySet()) {
                  try {
                    filesystem.createSymLink(
                        entry.getKey(),
                        entry.getKey().getParent().relativize(entry.getValue()),
                        /* force */ false);
                  } catch (FileAlreadyExistsException e) {
                    // Verify that the symlink is valid then continue gracefully
                    if (!filesystem
                        .readSymLink(entry.getKey())
                        .equals(entry.getKey().getParent().relativize(entry.getValue()))) {
                      throw e;
                    }
                  }
                }
              }
            });
  }

  protected abstract Console createConsole();

  protected abstract BuckEventBus createEventBus(Console console);

  /** Deserializes the BuildableAndTarget corresponding to hash and builds it. */
  public void build(HashCode hash) throws IOException, StepFailedException, InterruptedException {
    final Instant start = Instant.now();
    Deserializer deserializer =
        new Deserializer(
            filesystemFunction,
            classFinder,
            buildContext::getSourcePathResolver,
            // TODO(cjhopman): This gets toolchains from the root cell (instead of the cell the rule
            // is in).
            toolchainProviderFunction.apply(Optional.empty()));

    BuildableAndTarget reconstructed;
    try (Scope ignored = LeafEvents.scope(eventBus, "deserializing")) {
      reconstructed =
          deserializer.deserialize(getProvider(dataRoot, hash), BuildableAndTarget.class);
    }

    try (Scope ignored = LeafEvents.scope(eventBus, "steps");
        CloseableWrapper<BuckEventBus> eventBusWrapper = getWaitEventsWrapper(eventBus)) {
      ProjectFilesystem filesystem =
          filesystemFunction.apply(reconstructed.target.getCell().getLegacyName());
      ModernBuildRule.injectFieldsIfNecessary(
          filesystem,
          reconstructed.target,
          reconstructed.buildable,
          new AbstractBuildRuleResolver() {
            @Override
            public Optional<BuildRule> getRuleOptional(BuildTarget buildTarget) {
              throw new RuntimeException("Cannot resolve rules in deserialized MBR state.");
            }
          });

      final Instant deserializationComplete = Instant.now();
      LOG.info(
          String.format(
              "Finished deserializing the rule at [%s], took %d ms. Running the build now.",
              new java.util.Date(),
              deserializationComplete.minusMillis(start.toEpochMilli()).toEpochMilli()));

      for (Step step :
          ModernBuildRule.stepsForBuildable(
              buildContext, reconstructed.buildable, filesystem, reconstructed.target)) {
        StepRunner.runStep(executionContext, step, Optional.of(reconstructed.target));
      }

      long duration =
          Instant.now().minusMillis(deserializationComplete.toEpochMilli()).toEpochMilli();
      writeDurationToFile(duration);
      LOG.info(
          String.format(
              "Finished running the build at [%s], took %d ms. Exiting buck now.",
              new java.util.Date(), duration));
    }
  }

  private void writeDurationToFile(long duration) {
    try (BufferedWriter writer = Files.newBufferedWriter(metadataPath)) {
      writer.write(Long.toString(duration));
    } catch (IOException e) {
      LOG.error(e);
    }
  }

  private CloseableWrapper<BuckEventBus> getWaitEventsWrapper(BuckEventBus buildEventBus) {
    return CloseableWrapper.of(
        buildEventBus,
        eventBus -> {
          // wait for event bus to process all pending events
          if (!eventBus.waitEvents(100)) {
            LOG.warn(
                "Event bus did not complete all events within timeout; event listener's data"
                    + " may be incorrect");
          }
        });
  }

  // TODO(cjhopman): The layout of this directory is just determined by what
  // ModernBuildRuleRemoteExecutionHelper does. We should extract these to a single place.
  private DataProvider getProvider(Path dir, HashCode hash) {
    Preconditions.checkState(Files.exists(dir), "Dir [%s] does not exist.", dir.toAbsolutePath());

    return new DataProvider() {
      @Override
      public InputStream getData() {
        try {
          Path path = dir.resolve(hash.toString()).resolve("__value__");
          return new BufferedInputStream(new FileInputStream(path.toFile()));
        } catch (FileNotFoundException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public DataProvider getChild(HashCode hash) {
        return getProvider(dir, hash);
      }
    };
  }
}
