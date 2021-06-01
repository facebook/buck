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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.label.Label;
import com.facebook.buck.core.model.label.LabelSyntaxException;
import com.facebook.buck.core.model.label.PackageIdentifier;
import com.facebook.buck.core.model.label.PathFragment;
import com.facebook.buck.core.model.label.RepositoryName;
import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.core.starlark.compatible.BuckStarlarkPrintHandler;
import com.facebook.buck.core.starlark.compatible.StarlarkExportable;
import com.facebook.buck.core.starlark.eventhandler.Event;
import com.facebook.buck.core.starlark.eventhandler.EventHandler;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.parser.api.FileManifest;
import com.facebook.buck.parser.api.FileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.implicit.ImplicitInclude;
import com.facebook.buck.parser.implicit.ImplicitIncludePath;
import com.facebook.buck.parser.implicit.PackageImplicitIncludesFinder;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.skylark.function.LoadSymbolsContext;
import com.facebook.buck.skylark.io.Globber;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.parser.context.ParseContext;
import com.facebook.buck.skylark.parser.context.ReadConfigContext;
import com.facebook.buck.util.types.Either;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.LoadedModule;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.ResolverModule;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;
import org.immutables.value.Value;

/** Abstract parser for files written using Skylark syntax. */
abstract class AbstractSkylarkFileParser<T extends FileManifest> implements FileParser<T> {

  protected final ProjectBuildFileParserOptions options;
  protected final EventHandler eventHandler;
  protected final BuckGlobals buckGlobals;

  private final ConcurrentHashMap<AbsPath, Either<Program, ExtensionData>> extensionCache;
  private final ConcurrentHashMap<Label, IncludesData> includesDataCache;
  private final PackageImplicitIncludesFinder packageImplicitIncludeFinder;
  private final AtomicReference<ImplicitlyLoadedExtension> globalImplicitIncludes =
      new AtomicReference<>();

  AbstractSkylarkFileParser(
      ProjectBuildFileParserOptions options, BuckGlobals buckGlobals, EventHandler eventHandler) {
    this.options = options;
    this.eventHandler = eventHandler;
    this.buckGlobals = buckGlobals;

    this.extensionCache = new ConcurrentHashMap<>();

    this.includesDataCache = new ConcurrentHashMap<>();

    this.packageImplicitIncludeFinder =
        PackageImplicitIncludesFinder.fromConfiguration(options.getPackageImplicitIncludes());
  }

  abstract BuckOrPackage getBuckOrPackage();

  abstract ParseResult getParseResult(
      Path parseFile,
      ParseContext context,
      ReadConfigContext readConfigContext,
      Globber globber,
      ImmutableList<String> loadedPaths);

  abstract Globber getGlobber(AbsPath parseFile);

  private ImplicitlyLoadedExtension loadImplicitInclude(ImplicitIncludePath path)
      throws IOException, InterruptedException {
    ExtensionData extensionData =
        loadExtensionFromImport(
            ImmutableLoadImport.ofImpl(
                implicitIncludeContainingLabel(),
                path.reconstructWithAtAndColon(),
                Location.BUILTIN),
            LoadStack.EMPTY);
    return ImmutableImplicitlyLoadedExtension.ofImpl(
        extensionData.getLoadTransitiveClosure(), extensionData.getExtension().getSymbols());
  }

  private ImplicitlyLoadedExtension loadGlobalImplicitIncludes()
      throws IOException, InterruptedException {
    ImplicitlyLoadedExtension globalImplicitIncludes = this.globalImplicitIncludes.get();
    if (globalImplicitIncludes != null) {
      return globalImplicitIncludes;
    }

    ImmutableList.Builder<ImplicitlyLoadedExtension> extensions = ImmutableList.builder();
    for (ImplicitIncludePath defaultInclude : options.getDefaultIncludes()) {
      extensions.add(loadImplicitInclude(defaultInclude));
    }

    ImplicitlyLoadedExtension mergedExtensions =
        ImplicitlyLoadedExtension.merge(extensions.build());

    for (; ; ) {
      ImplicitlyLoadedExtension get = this.globalImplicitIncludes.get();
      if (get != null) {
        return get;
      }
      if (this.globalImplicitIncludes.compareAndSet(null, mergedExtensions)) {
        return mergedExtensions;
      }
    }
  }

  private ImplicitlyLoadedExtension loadImplicitExtension(
      ForwardRelPath basePath, LoadStack loadStack) throws IOException, InterruptedException {
    if (getBuckOrPackage() != BuckOrPackage.BUCK) {
      return ImplicitlyLoadedExtension.empty();
    }

    Optional<ImplicitInclude> implicitInclude =
        packageImplicitIncludeFinder.findIncludeForBuildFile(basePath);
    if (!implicitInclude.isPresent()) {
      return loadGlobalImplicitIncludes();
    }

    // Only export requested symbols, and ensure that all requsted symbols are present.
    ExtensionData data =
        loadExtensionFromImport(
            ImmutableLoadImport.ofImpl(
                implicitIncludeContainingLabel(),
                implicitInclude.get().getRawImportLabel().reconstructWithAtAndColon(),
                Location.BUILTIN),
            loadStack);
    LoadedModule symbols = data.getExtension();
    ImmutableMap<String, String> expectedSymbols = implicitInclude.get().getSymbols();
    Builder<String, Object> loaded = ImmutableMap.builderWithExpectedSize(expectedSymbols.size());
    for (Entry<String, String> kvp : expectedSymbols.entrySet()) {
      Object symbol = symbols.getGlobal(kvp.getValue());
      if (symbol == null) {
        throw BuildFileParseException.createForUnknownParseError(
            String.format(
                "Could not find symbol '%s' in implicitly loaded extension '%s'",
                kvp.getValue(), implicitInclude.get().getLoadPath()));
      }
      loaded.put(kvp.getKey(), symbol);
    }

    return ImplicitlyLoadedExtension.merge(
        ImmutableList.of(
            loadGlobalImplicitIncludes(),
            ImmutableImplicitlyLoadedExtension.ofImpl(
                data.getLoadTransitiveClosure(), loaded.build())));
  }

  private Label implicitIncludeContainingLabel() {
    return Label.createUnvalidated(
        PackageIdentifier.create(
            RepositoryName.createFromValidStrippedName(options.getCellName()),
            PathFragment.EMPTY_FRAGMENT),
        "BUCK");
  }

  /** @return The parsed result defined in {@code parseFile}. */
  protected ParseResult parse(AbsPath parseFile)
      throws IOException, BuildFileParseException, InterruptedException {

    ForwardRelPath basePath = getBasePath(parseFile);
    Label containingLabel = createContainingLabel(basePath);
    LoadStack loadStack = LoadStack.top(Location.fromFile(parseFile.toString()));
    ImplicitlyLoadedExtension implicitLoad = loadImplicitExtension(basePath, loadStack);

    Program buildFileAst =
        parseSkylarkFile(
            parseFile, loadStack, getBuckOrPackage().fileKind, implicitLoad.getLoadedSymbols());
    Globber globber = getGlobber(parseFile);
    PackageContext packageContext =
        createPackageContext(basePath, globber, implicitLoad.getLoadedSymbols());
    ParseContext parseContext = new ParseContext(packageContext);
    ReadConfigContext readConfigContext = new ReadConfigContext(packageContext.getRawConfig());
    try (Mutability mutability = Mutability.create("parsing " + parseFile)) {
      EnvironmentData envData =
          createBuildFileEvaluationEnvironment(
              parseFile,
              containingLabel,
              buildFileAst,
              mutability,
              parseContext,
              readConfigContext,
              implicitLoad.getLoadTransitiveClosure());

      Module module = new Module(buildFileAst.getModule());
      exec(loadStack, buildFileAst, module, envData.getEnvironment(), "file %s", parseFile);

      ImmutableList.Builder<String> loadedPaths =
          ImmutableList.builderWithExpectedSize(envData.getLoadedPaths().size() + 1);
      loadedPaths.add(parseFile.toString());
      loadedPaths.addAll(envData.getLoadedPaths());

      return getParseResult(
          parseFile.getPath(), parseContext, readConfigContext, globber, loadedPaths.build());
    }
  }

  private void exec(
      LoadStack loadStack,
      Program program,
      Module module,
      StarlarkThread thread,
      String what,
      Object... whatArgs)
      throws InterruptedException {
    try {
      Starlark.execFileProgram(program, module, thread);
    } catch (EvalException e) {
      String whatFormatted = String.format(what, whatArgs);
      throw new BuildFileParseException(
          e,
          loadStack.toDependencyStack(),
          "Cannot evaluate " + whatFormatted + "\n" + e.getMessageWithStack());
    } catch (InterruptedException | BuildFileParseException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof Starlark.UncheckedEvalException
          && e.getCause() instanceof BuildFileParseException) {
        // thrown by post-assign hook
        throw (BuildFileParseException) e.getCause();
      }
      throw new BuckUncheckedExecutionException(e, "When evaluating " + what, whatArgs);
    }
  }

  /**
   * @return The environment that can be used for evaluating build files. It includes built-in
   *     functions like {@code glob} and native rules like {@code java_library}.
   */
  private EnvironmentData createBuildFileEvaluationEnvironment(
      AbsPath buildFilePath,
      Label containingLabel,
      Program buildFileAst,
      Mutability mutability,
      ParseContext parseContext,
      ReadConfigContext readConfigContext,
      ImmutableSet<String> implicitLoadExtensionTransitiveClosure)
      throws IOException, InterruptedException, BuildFileParseException {
    ImmutableMap<String, ExtensionData> dependencies =
        loadExtensions(containingLabel, getImports(buildFileAst, containingLabel), LoadStack.EMPTY);
    StarlarkThread env = new StarlarkThread(mutability, BuckStarlark.BUCK_STARLARK_SEMANTICS);
    env.setPrintHandler(new BuckStarlarkPrintHandler(eventHandler));
    env.setLoader(Maps.transformValues(dependencies, ExtensionData::getExtension)::get);

    parseContext.setup(env);
    readConfigContext.setup(env);

    return ImmutableEnvironmentData.ofImpl(
        env,
        toLoadedPaths(
            buildFilePath, dependencies.values(), implicitLoadExtensionTransitiveClosure));
  }

  private PackageContext createPackageContext(
      ForwardRelPath basePath,
      Globber globber,
      ImmutableMap<String, Object> implicitlyLoadedSymbols) {
    return PackageContext.of(
        globber,
        options.getRawConfig(),
        options.getCellName(),
        basePath,
        eventHandler,
        implicitlyLoadedSymbols);
  }

  protected Label createContainingLabel(ForwardRelPath basePath) {
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
      AbsPath containingPath,
      ImmutableCollection<ExtensionData> dependencies,
      ImmutableSet<String> implicitLoadExtensionTransitiveClosure) {
    // expected size is used to reduce the number of unnecessary resize invocations
    int expectedSize = 1;
    expectedSize += implicitLoadExtensionTransitiveClosure.size();
    for (ExtensionData dependency : dependencies) {
      expectedSize += dependency.getLoadTransitiveClosure().size();
    }
    ImmutableList.Builder<String> loadedPathsBuilder =
        ImmutableList.builderWithExpectedSize(expectedSize);
    // for loop is used instead of foreach to avoid iterator overhead, since it's a hot spot
    loadedPathsBuilder.add(containingPath.toString());
    for (ExtensionData dependency : dependencies) {
      loadedPathsBuilder.addAll(dependency.getLoadTransitiveClosure());
    }
    loadedPathsBuilder.addAll(implicitLoadExtensionTransitiveClosure);
    return loadedPathsBuilder.build();
  }

  /**
   * Reads file and returns abstract syntax tree for that file.
   *
   * @param path file path to read the data from.
   * @return abstract syntax tree; does not handle any errors.
   */
  @VisibleForTesting
  protected StarlarkFile readSkylarkAST(AbsPath path) throws IOException {
    ParserInput input = ParserInput.fromUTF8(Files.readAllBytes(path.getPath()), path.toString());
    StarlarkFile file = StarlarkFile.parse(input);
    Event.replayEventsOn(eventHandler, file.errors());
    return file;
  }

  private ResolverModule makeModule(
      FileKind fileKind, ImmutableMap<String, Object> implicitIncludes) {
    if (fileKind == FileKind.BZL) {
      Preconditions.checkArgument(
          implicitIncludes.isEmpty(), "cannot use implicit includes when loading .bzl");
      return buckGlobals.makeBuckLoadContextGlobals();
    } else {
      return buckGlobals.makeBuckBuildFileContextGlobals(implicitIncludes);
    }
  }

  private Program parseSkylarkFile(
      AbsPath path,
      LoadStack loadStack,
      FileKind fileKind,
      ImmutableMap<String, Object> implicitIncludes)
      throws BuildFileParseException, IOException {
    StarlarkFile starlarkFile;
    try {
      starlarkFile = readSkylarkAST(path);
    } catch (NoSuchFileException e) {
      throw BuildFileParseException.createForUnknownParseError(
          loadStack.toDependencyStack(), "%s cannot be loaded because it does not exist", path);
    }
    if (!starlarkFile.errors().isEmpty()) {
      throw BuildFileParseException.createForUnknownParseError(
          loadStack.toDependencyStack(), "Cannot parse %s", path);
    }

    Event.replayEventsOn(eventHandler, starlarkFile.errors());

    if (!starlarkFile.errors().isEmpty()) {
      throw BuildFileParseException.createForUnknownParseError(
          loadStack.toDependencyStack(), "Cannot parse %s", path);
    }

    if (fileKind != FileKind.BZL) {
      if (!StarlarkBuckFileSyntax.checkBuildSyntax(starlarkFile, eventHandler)) {
        throw BuildFileParseException.createForUnknownParseError(
            loadStack.toDependencyStack(), "Cannot parse %s", path);
      }
    }

    Program result;
    try {
      ResolverModule module = makeModule(fileKind, implicitIncludes);
      result = Program.compileFile(starlarkFile, module);
      module.freeze();
    } catch (SyntaxError.Exception e) {
      Event.replayEventsOn(eventHandler, starlarkFile.errors());
      throw BuildFileParseException.createForUnknownParseError(
          loadStack.toDependencyStack(), "Cannot parse %s", path);
    }

    if (!starlarkFile.errors().isEmpty()) {
      throw BuildFileParseException.createForUnknownParseError(
          loadStack.toDependencyStack(), "Cannot parse %s", path);
    }

    return result;
  }

  private static ImmutableList<LoadImport> getImports(Program file, Label fileLabel) {
    return IntStream.range(0, file.getLoads().size())
        .mapToObj(
            i -> {
              String load = file.getLoads().get(i);
              Location location = file.getLoadLocation(i);
              return ImmutableLoadImport.ofImpl(fileLabel, load, location);
            })
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * Creates an {@code IncludesData} object from a {@code path}.
   *
   * @param loadImport an import label representing an extension to load.
   */
  private IncludesData loadIncludeImpl(LoadImport loadImport, LoadStack loadStack)
      throws IOException, BuildFileParseException, InterruptedException {
    Label label = loadImport.getLabel();
    AbsPath filePath = getImportPath(label, loadImport.getImport());

    Program fileAst = parseSkylarkFile(filePath, loadStack, FileKind.BZL, ImmutableMap.of());
    ImmutableList<IncludesData> dependencies =
        loadIncludes(label, getImports(fileAst, label), loadStack);

    return ImmutableIncludesData.ofImpl(
        filePath,
        dependencies,
        toIncludedPaths(filePath.toString(), dependencies, ImmutableSet.of()));
  }

  /**
   * Creates an {@code IncludesData} object from a {@code path}.
   *
   * @param loadImport an import label representing an extension to load.
   */
  private IncludesData loadInclude(LoadImport loadImport, LoadStack loadStack)
      throws IOException, BuildFileParseException, InterruptedException {
    IncludesData includesData = includesDataCache.get(loadImport.getLabel());
    if (includesData != null) {
      return includesData;
    }
    includesData = loadIncludeImpl(loadImport, loadStack);
    includesDataCache.put(loadImport.getLabel(), includesData);
    return includesData;
  }

  /** Collects all the included files identified by corresponding Starlark imports. */
  private ImmutableList<IncludesData> loadIncludes(
      Label containingLabel, ImmutableList<LoadImport> skylarkImports, LoadStack loadStack)
      throws BuildFileParseException, IOException, InterruptedException {
    Set<String> processed = new HashSet<>(skylarkImports.size());
    ImmutableList.Builder<IncludesData> includes =
        ImmutableList.builderWithExpectedSize(skylarkImports.size());
    // foreach is not used to avoid iterator overhead
    for (int i = 0; i < skylarkImports.size(); ++i) {
      LoadImport skylarkImport = skylarkImports.get(i);

      Preconditions.checkState(containingLabel.equals(skylarkImport.getContainingLabel()));

      // sometimes users include the same extension multiple times...
      if (!processed.add(skylarkImport.getImport())) continue;
      try {
        includes.add(
            loadInclude(skylarkImport, loadStack.child(skylarkImport.getImportLocation())));
      } catch (UncheckedExecutionException e) {
        propagateRootCause(e);
      }
    }
    return includes.build();
  }

  private ImmutableSet<String> toIncludedPaths(
      String containingPath,
      ImmutableList<IncludesData> dependencies,
      ImmutableSet<String> implicitLoadExtensionTransitiveClosure) {
    ImmutableSet.Builder<String> includedPathsBuilder = ImmutableSet.builder();
    includedPathsBuilder.add(containingPath);
    // for loop is used instead of foreach to avoid iterator overhead, since it's a hot spot
    for (int i = 0; i < dependencies.size(); ++i) {
      includedPathsBuilder.addAll(dependencies.get(i).getLoadTransitiveClosure());
    }
    includedPathsBuilder.addAll(implicitLoadExtensionTransitiveClosure);
    return includedPathsBuilder.build();
  }

  /** Loads all extensions identified by corresponding imports. */
  protected ImmutableMap<String, ExtensionData> loadExtensions(
      Label containingLabel, ImmutableList<LoadImport> skylarkImports, LoadStack loadStack)
      throws BuildFileParseException, IOException, InterruptedException {
    Set<String> processed = new HashSet<>(skylarkImports.size());
    ImmutableMap.Builder<String, ExtensionData> extensions =
        ImmutableMap.builderWithExpectedSize(skylarkImports.size());
    // foreach is not used to avoid iterator overhead
    for (int i = 0; i < skylarkImports.size(); ++i) {
      LoadImport skylarkImport = skylarkImports.get(i);

      Preconditions.checkState(containingLabel.equals(skylarkImport.getContainingLabel()));

      // sometimes users include the same extension multiple times...
      if (!processed.add(skylarkImport.getImport())) continue;
      try {
        extensions.put(
            skylarkImport.getImport(),
            loadExtensionFromImport(
                skylarkImport, loadStack.child(skylarkImport.getImportLocation())));
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
   * A struct like class to help loadExtension implementation to represent the state needed for load
   * of a single extension/file.
   */
  @VisibleForTesting
  static class LocalExtensionLoadState {
    // Extension key being loaded.
    private final LoadImport load;
    // Path for the extension.
    private final AbsPath path;
    // Load path
    private final LoadStack loadStack;
    // List of dependencies this extension uses.
    private final Set<LoadImport> dependencies;
    // This extension AST.
    private @Nullable Program ast;

    private LocalExtensionLoadState(LoadImport load, AbsPath extensionPath, LoadStack loadStack) {
      this.load = load;
      this.path = extensionPath;
      this.loadStack = loadStack;
      this.dependencies = new HashSet<LoadImport>();
      this.ast = null;
    }

    public AbsPath getPath() {
      return path;
    }

    // Methods to get/set/test AST for this extension.
    public boolean haveAST() {
      return ast != null;
    }

    public void setAST(Program ast) {
      Preconditions.checkArgument(!haveAST(), "AST can be set only once");
      this.ast = ast;
    }

    public Program getAST() {
      Preconditions.checkNotNull(ast);
      return ast;
    }

    // Adds a single dependency key for this extension.
    public void addDependency(LoadImport dependency) {
      Preconditions.checkArgument(dependency.getContainingLabel().equals(load.getLabel()));
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
  }

  /*
   * Given the list of load imports, returns the list of extension data corresponding to those loads.
   * Requires all of the extensions are available in the extension data  cache.
   *
   * @param label {@link Label} identifying extension with dependencies
   * @param dependencies list of load import dependencies
   * @returns list of ExtensionData
   */
  private ImmutableMap<String, ExtensionData> getDependenciesExtensionData(
      Label label, Set<LoadImport> dependencies) throws BuildFileParseException {
    HashMap<String, ExtensionData> depBuilder = new HashMap<>();

    for (LoadImport dependency : dependencies) {
      ExtensionData extension =
          lookupExtensionForImport(getImportPath(dependency.getLabel(), dependency.getImport()));

      if (extension == null) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot evaluate extension file %s; missing dependency is %s",
            label, dependency.getLabel());
      }

      depBuilder.putIfAbsent(dependency.getImport(), extension);
    }

    return ImmutableMap.copyOf(depBuilder);
  }

  /**
   * Retrieves extension data from the cache, and returns a copy suitable for the specified skylark
   * import string.
   *
   * @param path a path for the extension to lookup
   * @return {@link ExtensionData} suitable for the requested extension and importString, or null if
   *     no such extension found.
   */
  private @Nullable ExtensionData lookupExtensionForImport(AbsPath path) {
    Either<Program, ExtensionData> either = extensionCache.get(path);
    return either != null && either.isRight() ? either.getRight() : null;
  }
  /**
   * Retrieves extension data from the cache, and returns a copy suitable for the specified skylark
   * import string.
   *
   * @param path a path for the extension to lookup
   * @return {@link ExtensionData} suitable for the requested extension and importString, or null if
   *     no such extension found.
   */
  private Either<Program, ExtensionData> lookupExtensionForImportOrLoadProgram(
      AbsPath path, LoadStack loadStack) throws IOException {
    Either<Program, ExtensionData> either = extensionCache.get(path);
    if (either != null) {
      return either;
    }

    Program program = parseSkylarkFile(path, loadStack, FileKind.BZL, ImmutableMap.of());
    either = Either.ofLeft(program);
    Either<Program, ExtensionData> prev = extensionCache.putIfAbsent(path, either);
    if (prev != null) {
      return prev;
    } else {
      return either;
    }
  }

  /** Store extension in cache and return actually stored extension. */
  private ExtensionData cacheExtension(AbsPath path, ExtensionData extensionData) {
    return extensionCache
        .compute(
            path,
            (k, oldValue) -> {
              if (oldValue != null && oldValue.isRight()) {
                // Someone else stored the extension.
                return oldValue;
              } else {
                return Either.ofRight(extensionData);
              }
            })
        .getRight();
  }

  /**
   * Updates extension load state with the list of its dependencies, and schedules any unsatisfied
   * dependencies to be loaded by adding those dependencies to the work queue.
   *
   * @param load {@link LocalExtensionLoadState} representing extension currently loaded
   * @param queue a work queue of extensions that still need to be loaded.
   * @return true if this extension has any unsatisfied dependencies
   */
  private boolean processExtensionDependencies(
      LocalExtensionLoadState load, ArrayDeque<LocalExtensionLoadState> queue) {
    // Update this load state with the list of its dependencies.
    // Schedule missing dependencies to be loaded.
    boolean haveUnsatisfiedDeps = false;

    ImmutableList<LoadImport> imports = getImports(load.getAST(), load.getLabel());

    for (int i = 0; i < imports.size(); ++i) {
      LoadImport dependency = imports.get(i);

      // Record dependency for this load.
      load.addDependency(dependency);
      AbsPath extensionPath = getImportPath(dependency.getLabel(), dependency.getImport());
      if (lookupExtensionForImport(extensionPath) == null) {
        // Schedule dependency to be loaded if needed.
        haveUnsatisfiedDeps = true;
        queue.push(
            new LocalExtensionLoadState(
                dependency, extensionPath, load.loadStack.child(dependency.getImportLocation())));
      }
    }
    return haveUnsatisfiedDeps;
  }

  /**
   * Given fully loaded extension represented by {@link LocalExtensionLoadState}, evaluates
   * extension and returns {@link ExtensionData}
   *
   * @param load {@link LocalExtensionLoadState} representing loaded extension
   * @returns {@link ExtensionData} for this extions.
   */
  @VisibleForTesting
  protected ExtensionData buildExtensionData(LocalExtensionLoadState load)
      throws InterruptedException {
    ImmutableMap<String, ExtensionData> dependencies =
        getDependenciesExtensionData(load.getLabel(), load.getDependencies());
    BuckStarlarkLoadedModule loadedExtension;
    try (Mutability mutability = Mutability.create("importing extension")) {

      // Create this extension.
      StarlarkThread extensionEnv =
          new StarlarkThread(mutability, BuckStarlark.BUCK_STARLARK_SEMANTICS);
      extensionEnv.setPrintHandler(new BuckStarlarkPrintHandler(eventHandler));
      extensionEnv.setLoader(Maps.transformValues(dependencies, ExtensionData::getExtension)::get);

      ReadConfigContext readConfigContext = new ReadConfigContext(options.getRawConfig());
      readConfigContext.setup(extensionEnv);

      LoadSymbolsContext loadSymbolsContext = new LoadSymbolsContext();

      loadSymbolsContext.setup(extensionEnv);

      extensionEnv.setPostAssignHook(
          (n, v) -> {
            try {
              ensureExportedIfExportable(load.getLabel(), n, v);
            } catch (EvalException e) {
              // TODO(nga): what about stack trace
              eventHandler.handle(Event.error(e.getDeprecatedLocation(), e.getMessage()));
              throw new BuildFileParseException(e, e.getMessage());
            }
          });

      Program ast = load.getAST();
      buckGlobals.getKnownUserDefinedRuleTypes().invalidateExtension(load.getLabel());
      ResolverModule resolverModule = ast.getModule();
      // Must be already frozen, but freeze again to be safe.
      resolverModule.freeze();
      Module module = new Module(resolverModule);
      exec(
          load.loadStack,
          ast,
          module,
          extensionEnv,
          "extension %s referenced from %s",
          load.getLabel(),
          load.getParentLabel());

      extensionEnv.mutability().freeze();

      loadedExtension = new BuckStarlarkLoadedModule(module, loadSymbolsContext.getLoadedSymbols());
    }

    return ImmutableExtensionData.ofImpl(
        loadedExtension,
        load.getPath(),
        dependencies.values(),
        toLoadedPaths(load.getPath(), dependencies.values(), ImmutableSet.of()));
  }

  /**
   * Call {@link StarlarkExportable#export(Label, String)} on any objects that are assigned to
   *
   * <p>This is primarily used to make sure that {@link SkylarkUserDefinedRule} and {@link
   * com.facebook.buck.core.rules.providers.impl.UserDefinedProvider} instances set their name
   * properly upon assignment
   *
   * @param identifier the name of the variable
   * @param lookedUp exported value
   */
  private void ensureExportedIfExportable(Label extensionLabel, String identifier, Object lookedUp)
      throws BuildFileParseException, EvalException {
    if (lookedUp instanceof StarlarkExportable) {
      StarlarkExportable exportable = (StarlarkExportable) lookedUp;
      if (!exportable.isExported()) {
        Preconditions.checkState(extensionLabel != null);
        exportable.export(extensionLabel, identifier);
        if (lookedUp instanceof SkylarkUserDefinedRule) {
          this.buckGlobals
              .getKnownUserDefinedRuleTypes()
              .addRule((SkylarkUserDefinedRule) exportable);
        }
      }
    }
  }

  /**
   * Creates an extension from a {@code path}.
   *
   * @param loadImport an import label representing an extension to load.
   */
  private ExtensionData loadExtensionFromImport(LoadImport loadImport, LoadStack loadStack)
      throws IOException, BuildFileParseException, InterruptedException {

    ExtensionData extension = null;
    ArrayDeque<LocalExtensionLoadState> work = new ArrayDeque<>();
    AbsPath extensionPath = getImportPath(loadImport.getLabel(), loadImport.getImport());
    work.push(new LocalExtensionLoadState(loadImport, extensionPath, loadStack));

    while (!work.isEmpty()) {
      LocalExtensionLoadState load = work.peek();
      Either<Program, ExtensionData> either =
          lookupExtensionForImportOrLoadProgram(load.getPath(), load.loadStack);
      extension = either.getRightOption().orElse(null);

      if (extension != null) {
        // It's possible that some lower level dependencies already loaded
        // this work item.  We're done with it, so pop the queue.
        work.pop();
        continue;
      }

      // Load BuildFileAST if needed.
      boolean astLoaded;
      if (load.haveAST()) {
        astLoaded = false;
      } else {
        load.setAST(either.getLeft());
        astLoaded = true;
      }
      boolean haveUnsatisfiedDeps = astLoaded && processExtensionDependencies(load, work);

      // NB: If we have unsatisfied dependencies, we don't do anything;
      // more importantly we do not pop the work queue in this case.
      // This load is kept on the queue until all of its dependencies are satisfied.

      if (!haveUnsatisfiedDeps) {
        // We are done with this load; build it and cache it.
        work.removeFirst();
        extension = buildExtensionData(load);
        extension = cacheExtension(load.getPath(), extension);
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
  private AbsPath getImportPath(Label containingLabel, String skylarkImport)
      throws BuildFileParseException {
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
      return options.getProjectRoot().resolve(relativeExtensionPath.toString());
    }
    // Skylark repositories have an "@" prefix, but Buck roots do not, so ignore it
    String repositoryName = repository.getName().substring(1);
    @Nullable AbsPath repositoryPath = options.getCellRoots().get(repositoryName);
    if (repositoryPath == null) {
      throw BuildFileParseException.createForUnknownParseError(
          skylarkImport + " references an unknown repository " + repositoryName);
    }
    return repositoryPath.resolve(relativeExtensionPath.toString());
  }

  private boolean isRelativeLoad(String skylarkImport) {
    return skylarkImport.startsWith(":");
  }

  /**
   * @return The path path of the provided {@code buildFile}. For example, for {@code
   *     /Users/foo/repo/src/bar/BUCK}, where {@code /Users/foo/repo} is the path to the repo, it
   *     would return {@code src/bar}.
   */
  protected ForwardRelPath getBasePath(AbsPath buildFile) {
    return Optional.ofNullable(options.getProjectRoot().relativize(buildFile).getParent())
        .map(ForwardRelPath::ofRelPath)
        .orElse(ForwardRelPath.EMPTY);
  }

  @Override
  public ImmutableSortedSet<String> getIncludedFiles(AbsPath parseFile)
      throws BuildFileParseException, InterruptedException, IOException {

    ForwardRelPath basePath = getBasePath(parseFile);
    Label containingLabel = createContainingLabel(basePath);
    ImplicitlyLoadedExtension implicitLoad = loadImplicitExtension(basePath, LoadStack.EMPTY);
    Program buildFileAst =
        parseSkylarkFile(
            parseFile,
            LoadStack.EMPTY,
            getBuckOrPackage().fileKind,
            implicitLoad.getLoadedSymbols());
    ImmutableList<IncludesData> dependencies =
        loadIncludes(containingLabel, getImports(buildFileAst, containingLabel), LoadStack.EMPTY);

    // it might be potentially faster to keep sorted sets for each dependency separately and just
    // merge sorted lists as we aggregate transitive close up
    // But Guava does not seem to have a built-in way of merging sorted lists/sets
    return ImmutableSortedSet.copyOf(
        toIncludedPaths(
            parseFile.toString(), dependencies, implicitLoad.getLoadTransitiveClosure()));
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

    abstract Location getImportLocation();

    /** Returns a label of file being imported. */
    @Value.Derived
    Label getLabel() {
      try {
        return getContainingLabel().getRelativeWithRemapping(getImport());
      } catch (LabelSyntaxException e) {
        throw BuildFileParseException.createForUnknownParseError(
            "Incorrect load location in %s: %s", getImportLocation(), e.getMessage());
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
    abstract ImmutableSet<String> getLoadTransitiveClosure();

    abstract ImmutableMap<String, Object> getLoadedSymbols();

    private static class EmptyHolder {
      static ImplicitlyLoadedExtension EMPTY =
          ImmutableImplicitlyLoadedExtension.ofImpl(ImmutableSet.of(), ImmutableMap.of());
    }

    static ImplicitlyLoadedExtension empty() {
      return EmptyHolder.EMPTY;
    }

    static ImplicitlyLoadedExtension merge(List<ImplicitlyLoadedExtension> extensions) {
      if (extensions.isEmpty()) {
        return empty();
      } else if (extensions.size() == 1) {
        return extensions.get(0);
      } else {
        ImmutableSet.Builder<String> loadTransitiveClosure = ImmutableSet.builder();
        HashMap<String, Object> loadedSymbols = new HashMap<>();
        for (ImplicitlyLoadedExtension extension : extensions) {
          loadTransitiveClosure.addAll(extension.getLoadTransitiveClosure());
          for (Entry<String, Object> entry : extension.getLoadedSymbols().entrySet()) {
            Object prevValue = loadedSymbols.put(entry.getKey(), entry.getValue());
            if (prevValue != null) {
              throw new HumanReadableException(
                  "non-unique symbol in implicit include: %s", entry.getKey());
            }
          }
        }
        return ImmutableImplicitlyLoadedExtension.ofImpl(
            loadTransitiveClosure.build(), ImmutableMap.copyOf(loadedSymbols));
      }
    }
  }
}
