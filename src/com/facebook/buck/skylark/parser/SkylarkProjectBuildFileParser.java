/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.events.ParseBuckFileEvent;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.coercer.CoercedTypeCache;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.skylark.function.Glob;
import com.facebook.buck.skylark.function.NativeModule;
import com.facebook.buck.skylark.function.ReadConfig;
import com.facebook.buck.skylark.io.impl.SimpleGlobber;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.packages.PackageFactory;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.base.CaseFormat;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.syntax.BazelLibrary;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkImport;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Parser for build files written using Skylark syntax.
 *
 * <p>NOTE: This parser is still a work in progress and does not support some functions provided by
 * Python DSL parser like {@code include_defs}, so use in production at your own risk.
 */
public class SkylarkProjectBuildFileParser implements ProjectBuildFileParser {

  private static final Logger LOG = Logger.get(SkylarkProjectBuildFileParser.class);

  // internal variable exposed to rules that is used to track parse events. This allows us to
  // remove parse state from rules and as such makes rules reusable across parse invocations
  private static final String PARSE_CONTEXT = "$parse_context";
  private static final ImmutableSet<String> IMPLICIT_ATTRIBUTES =
      ImmutableSet.of("visibility", "within_view");
  private static final String PACKAGE_NAME_GLOBAL = "PACKAGE_NAME";
  // Dummy label used for resolving paths for other labels.
  private static final Label EMPTY_LABEL =
      Label.createUnvalidated(PackageIdentifier.EMPTY_PACKAGE_ID, "");

  private final FileSystem fileSystem;
  private final TypeCoercerFactory typeCoercerFactory;
  private final ProjectBuildFileParserOptions options;
  private final BuckEventBus buckEventBus;
  private final PrintingEventHandler eventHandler;
  private final Supplier<ImmutableList<BuiltinFunction>> buckRuleFunctionsSupplier;
  private final Supplier<NativeModule> nativeModuleSupplier;
  private final Supplier<Environment.Frame> buckGlobalsSupplier;
  private final BuiltinFunction readConfigFunction;

  private SkylarkProjectBuildFileParser(
      ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      TypeCoercerFactory typeCoercerFactory,
      PrintingEventHandler eventHandler) {
    this.options = options;
    this.buckEventBus = buckEventBus;
    this.fileSystem = fileSystem;
    this.typeCoercerFactory = typeCoercerFactory;
    this.eventHandler = eventHandler;
    // since Skylark parser is currently disabled by default, avoid creating functions in case
    // it's never used
    // TODO(ttsugrii): replace suppliers with eager loading once Skylark parser is on by default
    this.buckRuleFunctionsSupplier = Suppliers.memoize(this::getBuckRuleFunctions)::get;
    this.nativeModuleSupplier =
        Suppliers.memoize(() -> new NativeModule(buckRuleFunctionsSupplier.get(), Glob.create()))
            ::get;
    this.buckGlobalsSupplier = Suppliers.memoize(this::getBuckGlobals)::get;
    this.readConfigFunction = ReadConfig.create();
  }

  /** Create an instance of Skylark project build file parser using provided options. */
  public static SkylarkProjectBuildFileParser using(
      ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      TypeCoercerFactory typeCoercerFactory) {
    return new SkylarkProjectBuildFileParser(
        options,
        buckEventBus,
        fileSystem,
        typeCoercerFactory,
        new PrintingEventHandler(EnumSet.allOf(EventKind.class)));
  }

  @Override
  public ImmutableList<Map<String, Object>> getAll(Path buildFile, AtomicLong processedBytes)
      throws BuildFileParseException, InterruptedException, IOException {
    return parseBuildFile(buildFile).getRawRules();
  }

  @Override
  public ImmutableList<Map<String, Object>> getAllRulesAndMetaRules(
      Path buildFile, AtomicLong processedBytes)
      throws BuildFileParseException, InterruptedException, IOException {
    // TODO(ttsugrii): add metadata rules
    ParseResult parseResult = parseBuildFile(buildFile);
    // TODO(ttsugrii): find a way to reuse the same constants across Python DSL and Skylark parsers
    return ImmutableList.<Map<String, Object>>builder()
        .addAll(parseResult.getRawRules())
        .add(
            ImmutableMap.of(
                "__includes",
                parseResult
                    .getLoadedPaths()
                    .stream()
                    .map(Object::toString)
                    .collect(MoreCollectors.toImmutableSortedSet())))
        // TODO(ttsugrii): implement once configuration options are exposed via Skylark API
        .add(ImmutableMap.of("__configs", ImmutableMap.of()))
        // TODO(ttsugrii): implement once environment variables are exposed via Skylark API
        .add(ImmutableMap.of("__env", ImmutableMap.of()))
        .build();
  }

  /**
   * Retrieves build files requested in {@code buildFile}.
   *
   * @param buildFile The build file to parse.
   * @return The {@link ParseResult} with build rules defined in {@code buildFile}.
   */
  private ParseResult parseBuildFile(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    ImmutableList<Map<String, Object>> rules = ImmutableList.of();
    ParseBuckFileEvent.Started startEvent = ParseBuckFileEvent.started(buildFile);
    buckEventBus.post(startEvent);
    ParseResult parseResult;
    try {
      parseResult = parseBuildRules(buildFile);
      rules = parseResult.getRawRules();
    } finally {
      // TODO(ttsugrii): think about reporting processed bytes and profiling support
      buckEventBus.post(ParseBuckFileEvent.finished(startEvent, rules, 0L, Optional.empty()));
    }
    return parseResult;
  }

  /** @return The parsed build rules defined in {@code buildFile}. */
  private ParseResult parseBuildRules(Path buildFile)
      throws IOException, BuildFileParseException, InterruptedException {
    // TODO(ttsugrii): consider using a less verbose event handler. Also fancy handler can be
    // configured for terminals that support it.
    com.google.devtools.build.lib.vfs.Path buildFilePath = fileSystem.getPath(buildFile.toString());
    BuildFileAST buildFileAst =
        BuildFileAST.parseBuildFile(ParserInputSource.create(buildFilePath), eventHandler);
    if (buildFileAst.containsErrors()) {
      throw BuildFileParseException.createForUnknownParseError(
          "Cannot parse build file " + buildFile);
    }
    ParseContext parseContext = new ParseContext();
    try (Mutability mutability = Mutability.create("parsing " + buildFile)) {
      Environment env =
          createBuildFileEvaluationEnvironment(buildFile, buildFileAst, mutability, parseContext);
      boolean exec = buildFileAst.exec(env, eventHandler);
      if (!exec) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot evaluate build file " + buildFile);
      }
      parseContext.recordLoadedPath(buildFilePath);
      ImmutableList<Map<String, Object>> rules = parseContext.getRecordedRules();
      LOG.verbose("Got rules: %s", rules);
      LOG.verbose("Parsed %d rules from %s", rules.size(), buildFile);
      return ParseResult.builder()
          .setRawRules(rules)
          .setLoadedPaths(parseContext.getLoadedPaths())
          .build();
    }
  }

  /**
   * @return The environment that can be used for evaluating build files. It includes built-in
   *     functions like {@code glob} and native rules like {@code java_library}.
   */
  private Environment createBuildFileEvaluationEnvironment(
      Path buildFile, BuildFileAST buildFileAst, Mutability mutability, ParseContext parseContext)
      throws IOException, InterruptedException, BuildFileParseException {
    ImmutableMap<String, Environment.Extension> importMap =
        buildImportMap(buildFileAst.getImports(), parseContext);
    Environment env =
        Environment.builder(mutability)
            .setImportedExtensions(importMap)
            .setGlobals(buckGlobalsSupplier.get())
            .setPhase(Environment.Phase.LOADING)
            .useDefaultSemantics()
            .build();
    String basePath = getBasePath(buildFile);
    env.setupDynamic(PACKAGE_NAME_GLOBAL, basePath);
    env.setupDynamic(PARSE_CONTEXT, parseContext);
    env.setup("glob", Glob.create());
    PackageContext packageContext =
        PackageContext.builder()
            .setGlobber(SimpleGlobber.create(fileSystem.getPath(buildFile.getParent().toString())))
            .setRawConfig(options.getRawConfig())
            .build();
    env.setupDynamic(PackageFactory.PACKAGE_CONTEXT, packageContext);
    return env;
  }

  /**
   * @return The map from skylark import string like {@code //pkg:build_rules.bzl} to an {@link
   *     Environment.Extension}.
   */
  private ImmutableMap<String, Environment.Extension> buildImportMap(
      ImmutableList<SkylarkImport> skylarkImports, ParseContext parseContext)
      throws IOException, InterruptedException, BuildFileParseException {
    ImmutableMap.Builder<String, Environment.Extension> extensionMapBuilder =
        ImmutableMap.builder();
    for (SkylarkImport skylarkImport : skylarkImports) {
      try (Mutability mutability =
          Mutability.create("importing " + skylarkImport.getImportString())) {
        com.google.devtools.build.lib.vfs.Path extensionPath = getImportPath(skylarkImport);
        BuildFileAST extensionAst =
            BuildFileAST.parseSkylarkFile(ParserInputSource.create(extensionPath), eventHandler);
        if (extensionAst.containsErrors()) {
          throw BuildFileParseException.createForUnknownParseError(
              "Cannot parse extension file " + skylarkImport.getImportString());
        }
        Environment.Builder envBuilder =
            Environment.builder(mutability).setGlobals(buckGlobalsSupplier.get());
        if (!extensionAst.getImports().isEmpty()) {
          envBuilder.setImportedExtensions(buildImportMap(extensionAst.getImports(), parseContext));
        }
        Environment extensionEnv = envBuilder.useDefaultSemantics().build();
        extensionEnv.setup("native", nativeModuleSupplier.get());
        boolean success = extensionAst.exec(extensionEnv, eventHandler);
        if (!success) {
          throw BuildFileParseException.createForUnknownParseError(
              "Cannot evaluate extension file " + skylarkImport.getImportString());
        }
        Environment.Extension extension = new Environment.Extension(extensionEnv);
        extensionMapBuilder.put(skylarkImport.getImportString(), extension);
        parseContext.recordLoadedPath(extensionPath);
      }
    }
    return extensionMapBuilder.build();
  }

  /**
   * @return The environment frame with configured buck globals. This includes built-in rules like
   *     {@code java_library}.
   */
  private Environment.Frame getBuckGlobals() {
    Environment.Frame buckGlobals;
    try (Mutability mutability = Mutability.create("global")) {
      Environment globalEnv =
          Environment.builder(mutability)
              .setGlobals(BazelLibrary.GLOBALS)
              .useDefaultSemantics()
              .build();

      globalEnv.setup(readConfigFunction.getName(), readConfigFunction);
      for (BuiltinFunction buckRuleFunction : buckRuleFunctionsSupplier.get()) {
        globalEnv.setup(buckRuleFunction.getName(), buckRuleFunction);
      }
      buckGlobals = globalEnv.getGlobals();
    }
    return buckGlobals;
  }

  /**
   * @return The path to a Skylark extension. For example, for {@code load("//pkg:foo.bzl", "foo")}
   *     import it would return {@code /path/to/repo/pkg/foo.bzl} and for {@code
   *     load("@repo//pkg:foo.bzl", "foo")} it would return {@code /repo/pkg/foo.bzl} assuming that
   *     {@code repo} is located at {@code /repo}.
   */
  private com.google.devtools.build.lib.vfs.Path getImportPath(SkylarkImport skylarkImport)
      throws BuildFileParseException {
    Label extensionLabel = skylarkImport.getLabel(EMPTY_LABEL);
    PathFragment relativeExtensionPath = extensionLabel.toPathFragment();
    RepositoryName repository = extensionLabel.getPackageIdentifier().getRepository();
    if (repository.isMain()) {
      return fileSystem.getPath(
          options.getProjectRoot().resolve(relativeExtensionPath.toString()).toString());
    }
    // Skylark repositories have an "@" prefix, but Buck roots do not, so ignore it
    String repositoryName = repository.getName().substring(1);
    @Nullable Path repositoryPath = options.getCellRoots().get(repositoryName);
    if (repositoryPath == null) {
      throw BuildFileParseException.createForUnknownParseError(
          skylarkImport.getImportString() + " references an unknown repository " + repositoryName);
    }
    return fileSystem.getPath(repositoryPath.resolve(relativeExtensionPath.toString()).toString());
  }

  /**
   * @return The path path of the provided {@code buildFile}. For example, for {@code
   *     /Users/foo/repo/src/bar/BUCK}, where {@code /Users/foo/repo} is the path to the repo, it
   *     would return {@code src/bar}.
   */
  private String getBasePath(Path buildFile) {
    return Optional.ofNullable(options.getProjectRoot().relativize(buildFile).getParent())
        .map(MorePaths::pathWithUnixSeparators)
        .orElse("");
  }

  /**
   * @return The list of functions supporting all native Buck functions like {@code java_library}.
   */
  private ImmutableList<BuiltinFunction> getBuckRuleFunctions() {
    ImmutableList.Builder<BuiltinFunction> ruleFunctionsBuilder = ImmutableList.builder();
    for (Description<?> description : options.getDescriptions()) {
      ruleFunctionsBuilder.add(newRuleDefinition(description));
    }
    return ruleFunctionsBuilder.build();
  }

  /**
   * Create a Skylark definition for the {@code ruleClass} rule.
   *
   * <p>This makes functions like @{code java_library} available in build files. All they do is
   * capture passed attribute values in a map and adds them to the {@code ruleRegistry}.
   *
   * @param ruleClass The name of the rule to to define.
   * @return Skylark function to handle the Buck rule.
   */
  private BuiltinFunction newRuleDefinition(Description<?> ruleClass) {
    String name = Description.getBuildRuleType(ruleClass).getName();
    return new BuiltinFunction(
        name, FunctionSignature.KWARGS, BuiltinFunction.USE_AST_ENV, /*isRule=*/ true) {

      @SuppressWarnings({"unused"})
      public Runtime.NoneType invoke(
          Map<String, Object> kwargs, FuncallExpression ast, Environment env) throws EvalException {
        ImmutableMap.Builder<String, Object> builder =
            ImmutableMap.<String, Object>builder()
                .put("buck.base_path", env.lookup(PACKAGE_NAME_GLOBAL))
                .put("buck.type", name);
        ImmutableMap<String, ParamInfo> allParamInfo =
            CoercedTypeCache.INSTANCE.getAllParamInfo(
                typeCoercerFactory, ruleClass.getConstructorArgType());
        populateAttributes(kwargs, builder, allParamInfo);
        throwOnMissingRequiredAttribute(kwargs, allParamInfo);
        ParseContext parseContext = getParseContext(env, ast);
        parseContext.recordRule(builder.build());
        return Runtime.NONE;
      }
    };
  }

  /**
   * Validates attributes passed to the rule and in case any required attribute is not provided,
   * throws an {@link IllegalArgumentException}.
   *
   * @param kwargs The keyword arguments passed to the rule.
   * @param allParamInfo The mapping from build rule attributes to their information.
   */
  private void throwOnMissingRequiredAttribute(
      Map<String, Object> kwargs, ImmutableMap<String, ParamInfo> allParamInfo) {
    for (Map.Entry<String, ParamInfo> paramInfoEntry : allParamInfo.entrySet()) {
      String pythonName = paramInfoEntry.getValue().getPythonName();
      if (!paramInfoEntry.getValue().isOptional() && !kwargs.containsKey(pythonName)) {
        throw new IllegalArgumentException(pythonName + " is expected but not provided");
      }
    }
  }

  /**
   * Populates provided {@code builder} with values from {@code kwargs} assuming {@code ruleClass}
   * as the target {@link Description} class.
   *
   * @param kwargs The keyword arguments and their values passed to rule function in build file.
   * @param builder The map builder used for storing extracted attributes and their values.
   * @param allParamInfo The parameter information for every build rule attribute.
   */
  private void populateAttributes(
      Map<String, Object> kwargs,
      ImmutableMap.Builder<String, Object> builder,
      ImmutableMap<String, ParamInfo> allParamInfo) {
    for (Map.Entry<String, Object> kwargEntry : kwargs.entrySet()) {
      String paramName =
          CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, kwargEntry.getKey());
      if (!allParamInfo.containsKey(paramName)
          && !(IMPLICIT_ATTRIBUTES.contains(kwargEntry.getKey()))) {
        throw new IllegalArgumentException(kwargEntry.getKey() + " is not a recognized attribute");
      }
      if (Runtime.NONE.equals(kwargEntry.getValue())) {
        continue;
      }
      builder.put(paramName, kwargEntry.getValue());
    }
  }

  @Override
  public void reportProfile() throws IOException {
    // TODO(ttsugrii): implement
  }

  @Override
  public void close() throws BuildFileParseException, InterruptedException, IOException {
    // nothing to do
  }

  /** Get the {@link ParseContext} by looking up in the environment. */
  private static ParseContext getParseContext(Environment env, FuncallExpression ast)
      throws EvalException {
    @Nullable ParseContext value = (ParseContext) env.lookup(PARSE_CONTEXT);
    if (value == null) {
      // if PARSE_CONTEXT is missing, we're not called from a build file. This happens if someone
      // uses native.some_func() in the wrong place.
      throw new EvalException(
          ast.getLocation(),
          "The native module cannot be accessed from here. "
              + "Wrap the function in a macro and call it from a BUCK file");
    }
    return value;
  }
}
