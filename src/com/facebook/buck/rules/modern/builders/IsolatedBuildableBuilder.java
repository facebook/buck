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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.impl.AbstractSourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.EmbeddedCellBuckOutInfo;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.rules.modern.Deserializer;
import com.facebook.buck.rules.modern.Deserializer.DataProvider;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
  private final BuildContext buildContext;
  private final ExecutionContext executionContext;
  private final Function<Optional<String>, ProjectFilesystem> filesystemFunction;
  private final Deserializer.ClassFinder classFinder;
  private final Path dataRoot;
  private final BuckEventBus eventBus;

  @SuppressWarnings("PMD.EmptyCatchBlock")
  IsolatedBuildableBuilder(Path workRoot, Path projectRoot) throws IOException {
    Path canonicalWorkRoot = workRoot.toRealPath().normalize();
    Path canonicalProjectRoot = canonicalWorkRoot.resolve(projectRoot).normalize();

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
    Config config = Configs.createDefaultConfig(canonicalProjectRoot);
    ProjectFilesystemFactory projectFilesystemFactory = new DefaultProjectFilesystemFactory();

    // Root filesystemCell doesn't require embedded buck-out info.
    ProjectFilesystem filesystem;
    try {
      filesystem = projectFilesystemFactory.createProjectFilesystem(canonicalProjectRoot, config);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    ConcurrentHashMap<Path, ProjectFilesystem> filesystemMap = new ConcurrentHashMap<>();
    ConcurrentHashMap<Path, Config> configMap = new ConcurrentHashMap<>();

    Function<Path, Config> configFunction =
        (path) ->
            configMap.computeIfAbsent(
                path,
                ignored -> {
                  try {
                    return Configs.createDefaultConfig(path);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                });

    filesystemMap.put(canonicalProjectRoot, filesystem);

    Architecture architecture = Architecture.detect();
    Platform platform = Platform.detect();

    ImmutableMap<String, String> clientEnvironment = ImmutableMap.copyOf(System.getenv());

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.of(filesystem.getRootPath(), config);
    BuckConfig buckConfig =
        new BuckConfig(
            config,
            filesystem,
            architecture,
            platform,
            clientEnvironment,
            target ->
                BuildTargetParser.INSTANCE.parse(
                    target, BuildTargetPatternParser.fullyQualified(), cellPathResolver));

    this.filesystemFunction =
        (cellName) -> {
          Path cellPath =
              cellName == null
                  ? cellPathResolver.getCellPath(Optional.empty()).get()
                  : cellPathResolver.getCellPath(cellName).get();
          Preconditions.checkNotNull(cellPath);
          return filesystemMap.computeIfAbsent(
              cellPath,
              ignored -> {
                try {
                  Optional<EmbeddedCellBuckOutInfo> embeddedCellBuckOutInfo = Optional.empty();
                  Optional<String> canonicalCellName =
                      cellPathResolver.getCanonicalCellName(cellPath);
                  if (buckConfig.isEmbeddedCellBuckOutEnabled() && canonicalCellName.isPresent()) {
                    embeddedCellBuckOutInfo =
                        Optional.of(
                            EmbeddedCellBuckOutInfo.of(
                                filesystem.resolve(filesystem.getBuckPaths().getBuckOut()),
                                canonicalCellName.get()));
                  }
                  return projectFilesystemFactory.createProjectFilesystem(
                      cellPath, configFunction.apply(cellPath), embeddedCellBuckOutInfo);
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
              });
        };

    JavaPackageFinder javaPackageFinder =
        buckConfig.getView(JavaBuckConfig.class).createDefaultJavaPackageFinder();
    Console console = createConsole();
    this.eventBus = createEventBus(console);
    ProcessExecutor processExecutor = new DefaultProcessExecutor(console);

    this.executionContext =
        ExecutionContext.of(
            console,
            eventBus,
            platform,
            clientEnvironment,
            javaPackageFinder,
            ImmutableMap.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            cellPathResolver,
            canonicalProjectRoot,
            processExecutor,
            projectFilesystemFactory);

    this.buildContext =
        BuildContext.builder()
            .setSourcePathResolver(
                new AbstractSourcePathResolver() {
                  @Override
                  protected ProjectFilesystem getBuildTargetSourcePathFilesystem(
                      BuildTargetSourcePath sourcePath) {
                    Preconditions.checkState(sourcePath instanceof ExplicitBuildTargetSourcePath);
                    BuildTarget target = sourcePath.getTarget();
                    return filesystemFunction.apply(target.getCell());
                  }

                  @Override
                  protected SourcePath resolveDefaultBuildTargetSourcePath(
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
                })
            .setBuildCellRootPath(canonicalProjectRoot)
            .setEventBus(eventBus)
            .setJavaPackageFinder(javaPackageFinder)
            .setShouldDeleteTemporaries(buckConfig.getShouldDeleteTemporaries())
            .build();

    RichStream.from(cellPathResolver.getCellPaths().keySet())
        .forEachThrowing(
            name -> {
              // Sadly, some things assume this exists and writes to it.
              ProjectFilesystem fs = filesystemFunction.apply(Optional.of(name));
              BuckPaths configuredPaths = fs.getBuckPaths();
              Files.createDirectories(configuredPaths.getTmpDir());

              if (!configuredPaths.getConfiguredBuckOut().equals(configuredPaths.getBuckOut())
                  && buckConfig.getBuckOutCompatLink()
                  && Platform.detect() != Platform.WINDOWS) {
                BuckPaths unconfiguredPaths =
                    configuredPaths.withConfiguredBuckOut(configuredPaths.getBuckOut());
                ImmutableMap<Path, Path> paths =
                    ImmutableMap.of(
                        unconfiguredPaths.getGenDir(), configuredPaths.getGenDir(),
                        unconfiguredPaths.getScratchDir(), configuredPaths.getScratchDir());
                for (Map.Entry<Path, Path> entry : paths.entrySet()) {
                  filesystem.createSymLink(
                      entry.getKey(),
                      entry.getKey().getParent().relativize(entry.getValue()),
                      /* force */ false);
                }
              }
            });
  }

  protected abstract Console createConsole();

  protected abstract BuckEventBus createEventBus(Console console);

  /** Deserializes the BuildableAndTarget corresponding to hash and builds it. */
  public void build(HashCode hash) throws IOException, StepFailedException, InterruptedException {
    Deserializer deserializer =
        new Deserializer(filesystemFunction, classFinder, buildContext::getSourcePathResolver);

    BuildableAndTarget reconstructed;
    try (Scope ignored = LeafEvents.scope(eventBus, "deserializing")) {
      reconstructed =
          deserializer.deserialize(
              getProvider(dataRoot.resolve(hash.toString())), BuildableAndTarget.class);
    }

    try (Scope ignored = LeafEvents.scope(eventBus, "steps")) {
      for (Step step :
          ModernBuildRule.stepsForBuildable(
              buildContext,
              reconstructed.buildable,
              filesystemFunction.apply(reconstructed.target.getCell()),
              reconstructed.target)) {
        new DefaultStepRunner()
            .runStepForBuildTarget(executionContext, step, Optional.of(reconstructed.target));
      }
    }
  }

  private DataProvider getProvider(Path dir) {
    Preconditions.checkState(Files.exists(dir));

    return new DataProvider() {
      @Override
      public InputStream getData() {
        try {
          Path path = dir.resolve("__value__");
          return new BufferedInputStream(new FileInputStream(path.toFile()));
        } catch (FileNotFoundException e) {
          throw new RuntimeException(e);
        }
      }

      @Override
      public DataProvider getChild(HashCode hash) {
        return getProvider(dir.resolve(hash.toString()));
      }
    };
  }
}
