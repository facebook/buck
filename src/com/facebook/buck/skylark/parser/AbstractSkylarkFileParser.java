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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.parser.api.FileManifest;
import com.facebook.buck.parser.api.FileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.implicit.ImplicitInclude;
import com.facebook.buck.parser.implicit.PackageImplicitIncludesFinder;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.SkylarkExportable;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.LoadStatement;
import com.google.devtools.build.lib.syntax.Module;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInput;
import com.google.devtools.build.lib.syntax.SkylarkUtils;
import com.google.devtools.build.lib.syntax.SkylarkUtils.Phase;
import com.google.devtools.build.lib.syntax.StarlarkFile;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.syntax.ValidationEnvironment;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** Abstract parser for files written using Skylark syntax. */
abstract class AbstractSkylarkFileParser<T extends FileManifest> implements FileParser<T> {

  protected final FileSystem fileSystem;

  protected final ProjectBuildFileParserOptions options;
  protected final EventHandler eventHandler;
  protected final BuckGlobals buckGlobals;

  private final Cache<com.google.devtools.build.lib.vfs.Path, StarlarkFile> astCache;
  private final Cache<com.google.devtools.build.lib.vfs.Path, ExtensionData> extensionDataCache;
  private final LoadingCache<LoadImport, IncludesData> includesDataCache;
  private final PackageImplicitIncludesFinder packageImplicitIncludeFinder;

  AbstractSkylarkFileParser(
      ProjectBuildFileParserOptions options,
      FileSystem fileSystem,
      BuckGlobals buckGlobals,
      EventHandler eventHandler) {
    this.options = options;
    this.fileSystem = fileSystem;
    this.eventHandler = eventHandler;
    this.buckGlobals = buckGlobals;

    this.astCache = CacheBuilder.newBuilder().build();
    this.extensionDataCache = CacheBuilder.newBuilder().build();

    this.includesDataCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<LoadImport, IncludesData>() {
                  @Override
                  public IncludesData load(LoadImport loadImport)
                      throws IOException, InterruptedException {
                    return loadInclude(loadImport);
                  }
                });

    this.packageImplicitIncludeFinder =
        PackageImplicitIncludesFinder.fromConfiguration(options.getPackageImplicitIncludes());
  }

  abstract BuckOrPackage getBuckOrPackage();

  abstract ParseResult getParseResult(
      Path parseFile, ParseContext context, Globber globber, ImmutableList<String> loadedPaths);

  abstract Globber getGlobber(Path parseFile);

  private ImplicitlyLoadedExtension loadImplicitExtension(Path basePath, Label containingLabel)
      throws IOException, InterruptedException {
    Optional<ImplicitInclude> implicitInclude =
        packageImplicitIncludeFinder.findIncludeForBuildFile(basePath);
    if (!implicitInclude.isPresent()) {
      return ImplicitlyLoadedExtension.empty();
    }

    // Only export requested symbols, and ensure that all requsted symbols are present.
    ExtensionData data =
        loadExtension(
            ImmutableLoadImport.ofImpl(containingLabel, implicitInclude.get().getLoadPath()));
    ImmutableMap<String, Object> symbols = data.getExtension().getBindings();
    ImmutableMap<String, String> expectedSymbols = implicitInclude.get().getSymbols();
    Builder<String, Object> loaded = ImmutableMap.builderWithExpectedSize(expectedSymbols.size());
    for (Entry<String, String> kvp : expectedSymbols.entrySet()) {
      Object symbol = symbols.get(kvp.getValue());
      if (symbol == null) {
        throw BuildFileParseException.createForUnknownParseError(
            String.format(
                "Could not find symbol '%s' in implicitly loaded extension '%s'",
                kvp.getValue(), implicitInclude.get().getLoadPath()));
      }
      loaded.put(kvp.getKey(), symbol);
    }
    return ImmutableImplicitlyLoadedExtension.ofImpl(data, loaded.build());
  }

  /** @return The parsed result defined in {@code parseFile}. */
  protected ParseResult parse(AbsPath parseFile)
      throws IOException, BuildFileParseException, InterruptedException {
    com.google.devtools.build.lib.vfs.Path buildFilePath = fileSystem.getPath(parseFile.toString());

    ForwardRelativePath basePath = getBasePath(parseFile);
    Label containingLabel = createContainingLabel(basePath);
    ImplicitlyLoadedExtension implicitLoad =
        loadImplicitExtension(basePath.toPath(parseFile.getFileSystem()), containingLabel);

    StarlarkFile buildFileAst =
        parseSkylarkFile(buildFilePath, containingLabel, getBuckOrPackage().fileKind);
    Globber globber = getGlobber(parseFile.getPath());
    PackageContext packageContext =
        createPackageContext(basePath, globber, implicitLoad.getLoadedSymbols());
    ParseContext parseContext = new ParseContext(packageContext);
    try (Mutability mutability = Mutability.create("parsing " + parseFile)) {
      EnvironmentData envData =
          createBuildFileEvaluationEnvironment(
              buildFilePath,
              containingLabel,
              buildFileAst,
              mutability,
              parseContext,
              implicitLoad.getExtensionData());

      exec(buildFileAst, envData.getEnvironment(), "file %s", parseFile);

      ImmutableList.Builder<String> loadedPaths =
          ImmutableList.builderWithExpectedSize(envData.getLoadedPaths().size() + 1);
      loadedPaths.add(buildFilePath.toString());
      loadedPaths.addAll(envData.getLoadedPaths());

      return getParseResult(parseFile.getPath(), parseContext, globber, loadedPaths.build());
    }
  }

  private void exec(StarlarkFile file, StarlarkThread thread, String what, Object... whatArgs)
      throws InterruptedException {
    try {
      EvalUtils.exec(file, thread);
    } catch (EvalException e) {
      eventHandler.handle(Event.error(e.getLocation(), e.getMessage()));
      // buildFileAst.exec reports extended error information to console with eventHandler
      // but this is not propagated to BuildFileParseException. So in case of resilient parsing
      // when exceptions are stored in BuildFileManifest they do not have detailed information.
      // TODO(sergeyb): propagate detailed error information from AST evaluation to exception
      throw BuildFileParseException.createForUnknownParseError("Cannot evaluate " + what, whatArgs);
    } catch (InterruptedException | BuildFileParseException e) {
      throw e;
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(e, "When evaluating " + what, whatArgs);
    }
  }

  /**
   * @return The environment that can be used for evaluating build files. It includes built-in
   *     functions like {@code glob} and native rules like {@code java_library}.
   */
  private EnvironmentData createBuildFileEvaluationEnvironment(
      com.google.devtools.build.lib.vfs.Path buildFilePath,
      Label containingLabel,
      StarlarkFile buildFileAst,
      Mutability mutability,
      ParseContext parseContext,
      @Nullable ExtensionData implicitLoadExtensionData)
      throws IOException, InterruptedException, BuildFileParseException {
    ImmutableList<ExtensionData> dependencies =
        loadExtensions(containingLabel, getImports(buildFileAst));
    ImmutableMap<String, StarlarkThread.Extension> importMap =
        toImportMap(dependencies, implicitLoadExtensionData);
    StarlarkThread env =
        StarlarkThread.builder(mutability)
            .setImportedExtensions(importMap)
            .setGlobals(buckGlobals.getBuckBuildFileContextGlobals())
            .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
            .setEventHandler(eventHandler)
            .build();
    SkylarkUtils.setPhase(env, Phase.LOADING);

    parseContext.setup(env);

    return ImmutableEnvironmentData.ofImpl(
        env, toLoadedPaths(buildFilePath, dependencies, implicitLoadExtensionData));
  }

  private PackageContext createPackageContext(
      ForwardRelativePath basePath,
      Globber globber,
      ImmutableMap<String, Object> implicitlyLoadedSymbols) {
    return PackageContext.of(
        globber,
        options.getRawConfig(),
        PackageIdentifier.create(
            RepositoryName.createFromValidStrippedName(options.getCellName()),
            PathFragment.createAlreadyNormalized(basePath.toString())),
        eventHandler,
        implicitlyLoadedSymbols);
  }

  protected Label createContainingLabel(ForwardRelativePath basePath) {
    return Label.createUnvalidated(
        PackageIdentifier.create(
            RepositoryName.createFromValidStrippedName(options.getCellName()),
            PathFragment.createAlreadyNormalized(basePath.toString())),
        "BUCK");
  }

  /**
   * @param containingPath the path of the build or extension file that has provided dependencies.
   * @param dependencies the list of extension dependencies that {@code containingPath} has.
   * @return transitive closure of all paths loaded during parsing of {@code containingPath}
   *     including {@code containingPath} itself as the first element.
   */
  private ImmutableList<String> toLoadedPaths(
      com.google.devtools.build.lib.vfs.Path containingPath,
      ImmutableList<ExtensionData> dependencies,
      @Nullable ExtensionData implicitLoadExtensionData) {
    // expected size is used to reduce the number of unnecessary resize invocations
    int expectedSize = 1;
    if (implicitLoadExtensionData != null) {
      expectedSize += implicitLoadExtensionData.getLoadTransitiveClosure().size();
    }
    for (int i = 0; i < dependencies.size(); ++i) {
      expectedSize += dependencies.get(i).getLoadTransitiveClosure().size();
    }
    ImmutableList.Builder<String> loadedPathsBuilder =
        ImmutableList.builderWithExpectedSize(expectedSize);
    // for loop is used instead of foreach to avoid iterator overhead, since it's a hot spot
    loadedPathsBuilder.add(containingPath.toString());
    for (int i = 0; i < dependencies.size(); ++i) {
      loadedPathsBuilder.addAll(dependencies.get(i).getLoadTransitiveClosure());
    }
    if (implicitLoadExtensionData != null) {
      loadedPathsBuilder.addAll(implicitLoadExtensionData.getLoadTransitiveClosure());
    }
    return loadedPathsBuilder.build();
  }

  /**
   * Reads file and returns abstract syntax tree for that file.
   *
   * @param path file path to read the data from.
   * @return abstract syntax tree; does not handle any errors.
   */
  @VisibleForTesting
  protected StarlarkFile readSkylarkAST(com.google.devtools.build.lib.vfs.Path path)
      throws IOException {
    ParserInput input =
        ParserInput.create(
            FileSystemUtils.readContent(path, StandardCharsets.UTF_8), path.asFragment());
    StarlarkFile file = StarlarkFile.parse(input);
    Event.replayEventsOn(eventHandler, file.errors());
    return file;
  }

  private static final boolean ENABLE_PROPER_VALIDATION = false;

  private Module getGlobals(FileKind fileKind) {
    return fileKind == FileKind.BZL
        ? buckGlobals.getBuckLoadContextGlobals()
        : buckGlobals.getBuckBuildFileContextGlobals();
  }

  private StarlarkFile parseSkylarkFile(
      com.google.devtools.build.lib.vfs.Path path, Label containingLabel, FileKind fileKind)
      throws BuildFileParseException, IOException {
    StarlarkFile result = astCache.getIfPresent(path);
    if (result == null) {
      try {
        result = readSkylarkAST(path);
      } catch (FileNotFoundException e) {
        throw BuildFileParseException.createForUnknownParseError(
            "%s cannot be loaded because it does not exist. It was referenced from %s",
            path, containingLabel);
      }
      if (!result.errors().isEmpty()) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot parse %s.  It was referenced from %s", path, containingLabel);
      }

      // TODO(nga): these enable proper file validation (e. g. denies global redefinition)
      //   but nice stack trace becomes unavailable.
      //   So for example, ParserIntegrationTest fails
      if (ENABLE_PROPER_VALIDATION) {
        ValidationEnvironment.validateFile(
            result, getGlobals(fileKind), BuckStarlark.BUCK_STARLARK_SEMANTICS, false);
        Event.replayEventsOn(eventHandler, result.errors());

        if (!result.errors().isEmpty()) {
          throw BuildFileParseException.createForUnknownParseError(
              "Cannot parse %s.  It was referenced from %s", path, containingLabel);
        }
      }

      if (fileKind != FileKind.BZL) {
        if (!StarlarkBuckFileSyntax.checkBuildSyntax(result, eventHandler)) {
          throw BuildFileParseException.createForUnknownParseError(
              "Cannot parse %s.  It was referenced from %s", path, containingLabel);
        }
      }

      astCache.put(path, result);
    }
    return result;
  }

  private static ImmutableList<String> getImports(StarlarkFile file) {
    return file.getStatements().stream()
        .flatMap(
            s -> {
              if (s instanceof LoadStatement) {
                return Stream.of(((LoadStatement) s).getImport().getValue());
              } else {
                return Stream.empty();
              }
            })
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * Creates an {@code IncludesData} object from a {@code path}.
   *
   * @param loadImport an import label representing an extension to load.
   */
  private IncludesData loadInclude(LoadImport loadImport)
      throws IOException, BuildFileParseException, InterruptedException {
    Label label = loadImport.getLabel();
    com.google.devtools.build.lib.vfs.Path filePath = getImportPath(label, loadImport.getImport());

    StarlarkFile fileAst =
        parseSkylarkFile(filePath, loadImport.getContainingLabel(), FileKind.BZL);

    ImmutableList<IncludesData> dependencies = loadIncludes(label, getImports(fileAst));

    return ImmutableIncludesData.ofImpl(
        filePath, dependencies, toIncludedPaths(filePath.toString(), dependencies, null));
  }

  /** Collects all the included files identified by corresponding Starlark imports. */
  private ImmutableList<IncludesData> loadIncludes(
      Label containingLabel, ImmutableList<String> skylarkImports)
      throws BuildFileParseException, IOException, InterruptedException {
    Set<String> processed = new HashSet<>(skylarkImports.size());
    ImmutableList.Builder<IncludesData> includes =
        ImmutableList.builderWithExpectedSize(skylarkImports.size());
    // foreach is not used to avoid iterator overhead
    for (int i = 0; i < skylarkImports.size(); ++i) {
      String skylarkImport = skylarkImports.get(i);
      // sometimes users include the same extension multiple times...
      if (!processed.add(skylarkImport)) continue;
      LoadImport loadImport = ImmutableLoadImport.ofImpl(containingLabel, skylarkImport);
      try {
        includes.add(includesDataCache.getUnchecked(loadImport));
      } catch (UncheckedExecutionException e) {
        propagateRootCause(e);
      }
    }
    return includes.build();
  }

  private ImmutableSet<String> toIncludedPaths(
      String containingPath,
      ImmutableList<IncludesData> dependencies,
      @Nullable ExtensionData implicitLoadExtensionData) {
    ImmutableSet.Builder<String> includedPathsBuilder = ImmutableSet.builder();
    includedPathsBuilder.add(containingPath);
    // for loop is used instead of foreach to avoid iterator overhead, since it's a hot spot
    for (int i = 0; i < dependencies.size(); ++i) {
      includedPathsBuilder.addAll(dependencies.get(i).getLoadTransitiveClosure());
    }
    if (implicitLoadExtensionData != null) {
      includedPathsBuilder.addAll(implicitLoadExtensionData.getLoadTransitiveClosure());
    }
    return includedPathsBuilder.build();
  }

  /** Loads all extensions identified by corresponding imports. */
  protected ImmutableList<ExtensionData> loadExtensions(
      Label containingLabel, ImmutableList<String> skylarkImports)
      throws BuildFileParseException, IOException, InterruptedException {
    Set<String> processed = new HashSet<>(skylarkImports.size());
    ImmutableList.Builder<ExtensionData> extensions =
        ImmutableList.builderWithExpectedSize(skylarkImports.size());
    // foreach is not used to avoid iterator overhead
    for (int i = 0; i < skylarkImports.size(); ++i) {
      String skylarkImport = skylarkImports.get(i);
      // sometimes users include the same extension multiple times...
      if (!processed.add(skylarkImport)) continue;
      try {
        extensions.add(loadExtension(ImmutableLoadImport.ofImpl(containingLabel, skylarkImport)));
      } catch (UncheckedExecutionException e) {
        propagateRootCause(e);
      }
    }
    return extensions.build();
  }

  /**
   * Propagates underlying parse exception from {@link UncheckedExecutionException}.
   *
   * <p>This is an unfortunate consequence of having to use {@link
   * LoadingCache#getUnchecked(Object)} in when using stream transformations :(
   *
   * <p>TODO(ttsugrii): the logic of extracting root causes to make them user-friendly should be
   * happening somewhere in {@link com.facebook.buck.cli.MainRunner}, since this behavior is not
   * unique to parsing.
   */
  private void propagateRootCause(UncheckedExecutionException e)
      throws IOException, InterruptedException {
    Throwable rootCause = Throwables.getRootCause(e);
    if (rootCause instanceof BuildFileParseException) {
      throw (BuildFileParseException) rootCause;
    }
    if (rootCause instanceof IOException) {
      throw (IOException) rootCause;
    }
    if (rootCause instanceof InterruptedException) {
      throw (InterruptedException) rootCause;
    }
    throw e;
  }

  /**
   * @return The map from skylark import string like {@code //pkg:build_rules.bzl} to an {@link
   *     com.google.devtools.build.lib.syntax.StarlarkThread.Extension} for provided {@code
   *     dependencies}.
   */
  private ImmutableMap<String, StarlarkThread.Extension> toImportMap(
      ImmutableList<ExtensionData> dependencies,
      @Nullable ExtensionData implicitLoadExtensionData) {
    ImmutableMap.Builder<String, StarlarkThread.Extension> builder =
        ImmutableMap.builderWithExpectedSize(
            dependencies.size() + (implicitLoadExtensionData == null ? 0 : 1));
    // foreach is not used to avoid iterator overhead
    for (int i = 0; i < dependencies.size(); ++i) {
      ExtensionData extensionData = dependencies.get(i);
      builder.put(extensionData.getImportString(), extensionData.getExtension());
    }
    if (implicitLoadExtensionData != null) {
      builder.put(
          implicitLoadExtensionData.getImportString(), implicitLoadExtensionData.getExtension());
    }
    return builder.build();
  }

  /**
   * A struct like class to help loadExtension implementation to represent the state needed for load
   * of a single extension/file.
   */
  @VisibleForTesting
  class ExtensionLoadState {
    // Extension key being loaded.
    private final LoadImport load;
    // Path for the extension.
    private final com.google.devtools.build.lib.vfs.Path path;
    // List of dependencies this extension uses.
    private final Set<LoadImport> dependencies;
    // This extension AST.
    private @Nullable StarlarkFile ast;

    private ExtensionLoadState(
        LoadImport load, com.google.devtools.build.lib.vfs.Path extensionPath) {
      this.load = load;
      this.path = extensionPath;
      this.dependencies = new HashSet<LoadImport>();
      this.ast = null;
    }

    public com.google.devtools.build.lib.vfs.Path getPath() {
      return path;
    }

    // Methods to get/set/test AST for this extension.
    public boolean haveAST() {
      return ast != null;
    }

    public void setAST(StarlarkFile ast) {
      Preconditions.checkArgument(!haveAST(), "AST can be set only once");
      this.ast = ast;
    }

    public StarlarkFile getAST() {
      Preconditions.checkNotNull(ast);
      return ast;
    }

    // Adds a single dependency key for this extension.
    public void addDependency(LoadImport dependency) {
      dependencies.add(dependency);
    }

    // Returns the list of dependencies for this extension.
    public Set<LoadImport> getDependencies() {
      return dependencies;
    }

    // Returns the label of the file including this extension.
    public Label getParentLabel() {
      return load.getContainingLabel();
    }

    // Returns this extensions label.
    public Label getLabel() {
      return load.getLabel();
    }

    // Returns starlark import string for this extension load
    public String getSkylarkImport() {
      return load.getImport();
    }
  }

  /*
   * Given the list of load imports, returns the list of extension data corresponding to those loads.
   * Requires all of the extensions are available in the extension data  cache.
   *
   * @param label {@link Label} identifying extension with dependencies
   * @param dependencies list of load import dependencies
   * @returns list of ExtensionData
   */
  private ImmutableList<ExtensionData> getDependenciesExtensionData(
      Label label, Set<LoadImport> dependencies) throws BuildFileParseException {
    ImmutableList.Builder<ExtensionData> depBuilder =
        ImmutableList.builderWithExpectedSize(dependencies.size());

    for (LoadImport dependency : dependencies) {
      ExtensionData extension =
          lookupExtensionForImport(
              getImportPath(dependency.getLabel(), dependency.getImport()), dependency.getImport());

      if (extension == null) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot evaluate extension file %s; missing dependency is %s",
            label, dependency.getLabel());
      }

      depBuilder.add(extension);
    }

    return depBuilder.build();
  }

  /**
   * Retrieves extension data from the cache, and returns a copy suitable for the specified skylark
   * import string.
   *
   * @param path a path for the extension to lookup
   * @param importString a skylark import string for this extension load
   * @return {@link ExtensionData} suitable for the requested extension and importString, or null if
   *     no such extension found.
   */
  private @Nullable ExtensionData lookupExtensionForImport(
      com.google.devtools.build.lib.vfs.Path path, String importString) {
    ExtensionData ext = extensionDataCache.getIfPresent(path);
    return ext == null ? ext : ext.withImportString(importString);
  }

  /**
   * Loads extensions abstract syntax tree if needed.
   *
   * @param load {@link ExtensionLoadState} representing the extension being loaded.
   * @returns true if AST was loaded, false otherwise.
   */
  private boolean maybeLoadAST(ExtensionLoadState load) throws IOException {
    if (load.haveAST()) {
      return false;
    }
    load.setAST(parseSkylarkFile(load.getPath(), load.getParentLabel(), FileKind.BZL));
    return true;
  }

  /**
   * Updates extension load state with the list of its dependencies, and schedules any unsatisfied
   * dependencies to be loaded by adding those dependencies to the work queue.
   *
   * @param load {@link ExtensionLoadState} representing extension currently loaded
   * @param queue a work queue of extensions that still need to be loaded.
   * @return true if this extension has any unsatisfied dependencies
   */
  private boolean processExtensionDependencies(
      ExtensionLoadState load, ArrayDeque<ExtensionLoadState> queue) {
    // Update this load state with the list of its dependencies.
    // Schedule missing dependencies to be loaded.
    boolean haveUnsatisfiedDeps = false;

    ImmutableList<String> imports = getImports(load.getAST());

    for (int i = 0; i < imports.size(); ++i) {
      LoadImport dependency = ImmutableLoadImport.ofImpl(load.getLabel(), imports.get(i));

      // Record dependency for this load.
      load.addDependency(dependency);
      com.google.devtools.build.lib.vfs.Path extensionPath =
          getImportPath(dependency.getLabel(), dependency.getImport());
      if (extensionDataCache.getIfPresent(extensionPath) == null) {
        // Schedule dependency to be loaded if needed.
        haveUnsatisfiedDeps = true;
        queue.push(new ExtensionLoadState(dependency, extensionPath));
      }
    }
    return haveUnsatisfiedDeps;
  }

  /**
   * Given fully loaded extension represented by {@link ExtensionLoadState}, evaluates extension and
   * returns {@link ExtensionData}
   *
   * @param load {@link ExtensionLoadState} representing loaded extension
   * @returns {@link ExtensionData} for this extions.
   */
  @VisibleForTesting
  protected ExtensionData buildExtensionData(ExtensionLoadState load) throws InterruptedException {
    ImmutableList<ExtensionData> dependencies =
        getDependenciesExtensionData(load.getLabel(), load.getDependencies());
    StarlarkThread.Extension loadedExtension;
    try (Mutability mutability = Mutability.create("importing extension")) {
      StarlarkThread.Builder envBuilder =
          StarlarkThread.builder(mutability)
              .setEventHandler(eventHandler)
              .setGlobals(buckGlobals.getBuckLoadContextGlobals().withLabel(load.getLabel()));
      envBuilder.setImportedExtensions(toImportMap(dependencies, null));

      // Create this extension.
      StarlarkThread extensionEnv =
          envBuilder.setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS).build();

      extensionEnv.setPostAssignHook(
          (n, v) -> {
            try {
              ensureExportedIfExportable(extensionEnv, n, v);
            } catch (EvalException e) {
              // TODO(nga): what about stack trace
              eventHandler.handle(Event.error(e.getLocation(), e.getMessage()));
              throw new BuildFileParseException(e, e.getMessage());
            }
          });

      SkylarkUtils.setPhase(extensionEnv, Phase.LOADING);

      StarlarkFile ast = load.getAST();
      buckGlobals.getKnownUserDefinedRuleTypes().invalidateExtension(load.getLabel());
      exec(
          ast,
          extensionEnv,
          "extension %s referenced from %s",
          load.getLabel(),
          load.getParentLabel());

      loadedExtension = new StarlarkThread.Extension(extensionEnv);
    }

    return ImmutableExtensionData.ofImpl(
        loadedExtension,
        load.getPath(),
        dependencies,
        load.getSkylarkImport(),
        toLoadedPaths(load.getPath(), dependencies, null));
  }

  /**
   * Call {@link com.google.devtools.build.lib.packages.SkylarkExportable#export(Label, String)} on
   * any objects that are assigned to
   *
   * <p>This is primarily used to make sure that {@link SkylarkUserDefinedRule} and {@link
   * com.facebook.buck.core.rules.providers.impl.UserDefinedProvider} instances set their name
   * properly upon assignment
   *
   * @param extensionEnv The environment where an exportable variable with the name in {@code stmt}
   *     is bound
   * @param identifier the name of the variable
   * @param lookedUp exported value
   */
  private void ensureExportedIfExportable(
      StarlarkThread extensionEnv, String identifier, Object lookedUp)
      throws BuildFileParseException, EvalException {
    if (lookedUp instanceof SkylarkExportable) {
      SkylarkExportable exportable = (SkylarkExportable) lookedUp;
      if (!exportable.isExported()) {
        Label extensionLabel = (Label) extensionEnv.getGlobals().getLabel();
        if (extensionLabel != null) {
          exportable.export(extensionLabel, identifier);
          if (lookedUp instanceof SkylarkUserDefinedRule) {
            this.buckGlobals
                .getKnownUserDefinedRuleTypes()
                .addRule((SkylarkUserDefinedRule) exportable);
          }
        }
      }
    }
  }

  /**
   * Creates an extension from a {@code path}.
   *
   * @param loadImport an import label representing an extension to load.
   */
  private ExtensionData loadExtension(LoadImport loadImport)
      throws IOException, BuildFileParseException, InterruptedException {
    ExtensionData extension = null;
    ArrayDeque<ExtensionLoadState> work = new ArrayDeque<>();
    work.push(
        new ExtensionLoadState(
            loadImport, getImportPath(loadImport.getLabel(), loadImport.getImport())));

    while (!work.isEmpty()) {
      ExtensionLoadState load = work.peek();
      extension = lookupExtensionForImport(load.getPath(), load.getSkylarkImport());

      if (extension != null) {
        // It's possible that some lower level dependencies already loaded
        // this work item.  We're done with it, so pop the queue.
        work.pop();
        continue;
      }

      // Load BuildFileAST if needed.
      boolean astLoaded = maybeLoadAST(load);
      boolean haveUnsatisfiedDeps = astLoaded && processExtensionDependencies(load, work);

      // NB: If we have unsatisfied dependencies, we don't do anything;
      // more importantly we do not pop the work queue in this case.
      // This load is kept on the queue until all of its dependencies are satisfied.

      if (!haveUnsatisfiedDeps) {
        // We are done with this load; build it and cache it.
        work.removeFirst();
        extension = buildExtensionData(load);
        extensionDataCache.put(load.getPath(), extension);
      }
    }

    Preconditions.checkNotNull(extension);
    return extension;
  }

  /**
   * @return The path to a Skylark extension. For example, for {@code load("//pkg:foo.bzl", "foo")}
   *     import it would return {@code /path/to/repo/pkg/foo.bzl} and for {@code
   *     load("@repo//pkg:foo.bzl", "foo")} it would return {@code /repo/pkg/foo.bzl} assuming that
   *     {@code repo} is located at {@code /repo}.
   */
  private com.google.devtools.build.lib.vfs.Path getImportPath(
      Label containingLabel, String skylarkImport) throws BuildFileParseException {
    if (isRelativeLoad(skylarkImport) && skylarkImport.contains("/")) {
      throw BuildFileParseException.createForUnknownParseError(
          "Relative loads work only for files in the same directory but "
              + skylarkImport
              + " is trying to load a file from a nested directory. "
              + "Please use absolute label instead ([cell]//pkg[/pkg]:target).");
    }
    PathFragment relativeExtensionPath = containingLabel.toPathFragment();
    RepositoryName repository = containingLabel.getPackageIdentifier().getRepository();
    if (repository.isMain()) {
      return fileSystem.getPath(
          options.getProjectRoot().resolve(relativeExtensionPath.toString()).toString());
    }
    // Skylark repositories have an "@" prefix, but Buck roots do not, so ignore it
    String repositoryName = repository.getName().substring(1);
    @Nullable AbsPath repositoryPath = options.getCellRoots().get(repositoryName);
    if (repositoryPath == null) {
      throw BuildFileParseException.createForUnknownParseError(
          skylarkImport + " references an unknown repository " + repositoryName);
    }
    return fileSystem.getPath(repositoryPath.resolve(relativeExtensionPath.toString()).toString());
  }

  private boolean isRelativeLoad(String skylarkImport) {
    return skylarkImport.startsWith(":");
  }

  /**
   * @return The path path of the provided {@code buildFile}. For example, for {@code
   *     /Users/foo/repo/src/bar/BUCK}, where {@code /Users/foo/repo} is the path to the repo, it
   *     would return {@code src/bar}.
   */
  protected ForwardRelativePath getBasePath(AbsPath buildFile) {
    return Optional.ofNullable(options.getProjectRoot().relativize(buildFile).getParent())
        .map(ForwardRelativePath::ofRelPath)
        .orElse(ForwardRelativePath.EMPTY);
  }

  @Override
  public ImmutableSortedSet<String> getIncludedFiles(AbsPath parseFile)
      throws BuildFileParseException, InterruptedException, IOException {
    com.google.devtools.build.lib.vfs.Path buildFilePath = fileSystem.getPath(parseFile.toString());

    ForwardRelativePath basePath = getBasePath(parseFile);
    Label containingLabel = createContainingLabel(basePath);
    ImplicitlyLoadedExtension implicitLoad =
        loadImplicitExtension(basePath.toPath(parseFile.getFileSystem()), containingLabel);
    StarlarkFile buildFileAst =
        parseSkylarkFile(buildFilePath, containingLabel, getBuckOrPackage().fileKind);
    ImmutableList<IncludesData> dependencies =
        loadIncludes(containingLabel, getImports(buildFileAst));

    // it might be potentially faster to keep sorted sets for each dependency separately and just
    // merge sorted lists as we aggregate transitive close up
    // But Guava does not seem to have a built-in way of merging sorted lists/sets
    return ImmutableSortedSet.copyOf(
        toIncludedPaths(parseFile.toString(), dependencies, implicitLoad.getExtensionData()));
  }

  @Override
  public void close() throws BuildFileParseException {
    // nothing to do
  }

  /**
   * A value object for information about load function import, since import string does not provide
   * enough context. For instance, the same import string can represent different logical imports
   * depending on which repository it is resolved in.
   */
  @BuckStyleValue
  abstract static class LoadImport {
    /** Returns a label of the file containing this import. */
    abstract Label getContainingLabel();

    /** Returns a Skylark import. */
    abstract String getImport();

    /** Returns a label of current import file. */
    Label getLabel() {
      try {
        return getContainingLabel().getRelativeWithRemapping(getImport(), ImmutableMap.of());
      } catch (LabelSyntaxException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * A value object for information about implicit loads. This allows us to both validate implicit
   * import information, and return some additional information needed to setup build file
   * environments in one swoop.
   */
  @BuckStyleValue
  abstract static class ImplicitlyLoadedExtension {
    abstract @Nullable ExtensionData getExtensionData();

    abstract ImmutableMap<String, Object> getLoadedSymbols();

    @Value.Lazy
    static ImplicitlyLoadedExtension empty() {
      return ImmutableImplicitlyLoadedExtension.ofImpl(null, ImmutableMap.of());
    }
  }
}
