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

import bazel.shaded.com.google.common.collect.ImmutableCollection;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.MorePaths;
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
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.BazelLibrary;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.ClassObject;
import com.google.devtools.build.lib.syntax.Environment;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Parser for build files written using Skylark syntax.
 *
 * <p>NOTE: This parser is work in progress and does not support many functions provided by Python
 * DSL parser like {@code read_config} and {@code include_defs} (currently implemented as a noop),
 * so DO NOT USE it production.
 */
public class SkylarkProjectBuildFileParser implements ProjectBuildFileParser {

  private static final Logger LOG = Logger.get(SkylarkProjectBuildFileParser.class);

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

  private SkylarkProjectBuildFileParser(
      final ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      TypeCoercerFactory typeCoercerFactory) {
    this.options = options;
    this.buckEventBus = buckEventBus;
    this.fileSystem = fileSystem;
    this.typeCoercerFactory = typeCoercerFactory;
  }

  public static SkylarkProjectBuildFileParser using(
      final ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      TypeCoercerFactory typeCoercerFactory) {
    return new SkylarkProjectBuildFileParser(options, buckEventBus, fileSystem, typeCoercerFactory);
  }

  @Override
  public ImmutableList<Map<String, Object>> getAll(Path buildFile, AtomicLong processedBytes)
      throws BuildFileParseException, InterruptedException, IOException {
    return getAllRulesAndMetaRules(buildFile, processedBytes);
  }

  @Override
  public ImmutableList<Map<String, Object>> getAllRulesAndMetaRules(
      Path buildFile, AtomicLong processedBytes)
      throws BuildFileParseException, InterruptedException, IOException {
    ParseBuckFileEvent.Started startEvent = ParseBuckFileEvent.started(buildFile);
    buckEventBus.post(startEvent);
    ImmutableList<Map<String, Object>> rules = ImmutableList.of();
    try {
      rules = parseBuildRules(buildFile);
      LOG.verbose("Got rules: %s", rules);
      LOG.verbose("Parsed %d rules from %s", rules.size(), buildFile);
    } finally {
      // TODO(ttsugrii): think about reporting processed bytes and profiling support
      buckEventBus.post(ParseBuckFileEvent.finished(startEvent, rules, 0L, Optional.empty()));
    }
    return rules;
  }

  /**
   * Parses and returns build rules defined in {@code buildFile}.
   *
   * @param buildFile The build file to parse.
   * @return The build rules defined in {@code buildFile}.
   */
  private ImmutableList<Map<String, Object>> parseBuildRules(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    // TODO(ttsugrii): consider using a less verbose event handler. Also fancy handler can be
    // configured for terminals that support it.
    PrintingEventHandler eventHandler = new PrintingEventHandler(EnumSet.allOf(EventKind.class));
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
          createBuildFileEvaluationEnvironment(
              buildFile, buildFilePath, buildFileAst, eventHandler, mutability, parseContext);
      boolean exec = buildFileAst.exec(env, eventHandler);
      if (!exec) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot evaluate build file " + buildFile);
      }
      return parseContext.getRecordedRules();
    }
  }

  /**
   * @return The environment that can be used for evaluating build files. It includes built-in
   *     functions like {@code glob} and native rules like {@code java_library}.
   */
  private Environment createBuildFileEvaluationEnvironment(
      Path buildFile,
      com.google.devtools.build.lib.vfs.Path buildFilePath,
      BuildFileAST buildFileAst,
      PrintingEventHandler eventHandler,
      Mutability mutability,
      ParseContext parseContext)
      throws IOException, InterruptedException, BuildFileParseException {
    Environment.Frame buckGlobals = getBuckGlobals();
    ImmutableList<BuiltinFunction> buckRuleFunctions = getBuckRuleFunctions(parseContext);
    ImmutableMap<String, Environment.Extension> importMap =
        buildImportMap(buildFileAst.getImports(), buckGlobals, buckRuleFunctions, eventHandler);
    Environment env =
        Environment.builder(mutability)
            .setImportedExtensions(importMap)
            .setGlobals(buckGlobals)
            .setPhase(Environment.Phase.LOADING)
            .build();
    String basePath = getBasePath(buildFile);
    env.setupDynamic(PACKAGE_NAME_GLOBAL, basePath);
    env.setup("glob", Glob.create(buildFilePath.getParentDirectory()));
    for (BuiltinFunction buckRuleFunction : buckRuleFunctions) {
      env.setup(buckRuleFunction.getName(), buckRuleFunction);
    }
    return env;
  }

  /**
   * @return The map from skylark import string like {@code //pkg:build_rules.bzl} to an {@link
   *     Environment.Extension}.
   */
  private ImmutableMap<String, Environment.Extension> buildImportMap(
      bazel.shaded.com.google.common.collect.ImmutableList<SkylarkImport> skylarkImports,
      Environment.Frame buckGlobals,
      ImmutableList<BuiltinFunction> buckRuleFunctions,
      PrintingEventHandler eventHandler)
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
        Environment.Builder envBuilder = Environment.builder(mutability).setGlobals(buckGlobals);
        if (!extensionAst.getImports().isEmpty()) {
          envBuilder.setImportedExtensions(
              buildImportMap(
                  extensionAst.getImports(), buckGlobals, buckRuleFunctions, eventHandler));
        }
        Environment extensionEnv = envBuilder.build();
        extensionEnv.setup("native", new NativeModule(buckRuleFunctions));
        boolean success = extensionAst.exec(extensionEnv, eventHandler);
        if (!success) {
          throw BuildFileParseException.createForUnknownParseError(
              "Cannot evaluate extension file " + skylarkImport.getImportString());
        }
        Environment.Extension extension = new Environment.Extension(extensionEnv);
        extensionMapBuilder.put(skylarkImport.getImportString(), extension);
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
          Environment.builder(mutability).setGlobals(BazelLibrary.GLOBALS).build();
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
  private ImmutableList<BuiltinFunction> getBuckRuleFunctions(ParseContext parseContext) {
    ImmutableList.Builder<BuiltinFunction> ruleFunctionsBuilder = ImmutableList.builder();
    for (Description<?> description : options.getDescriptions()) {
      ruleFunctionsBuilder.add(newRuleDefinition(description, parseContext));
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
   * @param parseContext The parse context tracking useful information like recorded rules.
   * @return Skylark function to handle the Buck rule.
   */
  private BuiltinFunction newRuleDefinition(Description<?> ruleClass, ParseContext parseContext) {
    String name = Description.getBuildRuleType(ruleClass).getName();
    return new BuiltinFunction(
        name, FunctionSignature.KWARGS, BuiltinFunction.USE_AST_ENV, /*isRule=*/ true) {

      @SuppressWarnings({"unused"})
      public Runtime.NoneType invoke(
          Map<String, Object> kwargs, FuncallExpression ast, Environment env) {
        ImmutableMap.Builder<String, Object> builder =
            ImmutableMap.<String, Object>builder()
                .put("buck.base_path", env.lookup(PACKAGE_NAME_GLOBAL))
                .put("buck.type", name);
        ImmutableMap<String, ParamInfo> allParamInfo =
            CoercedTypeCache.INSTANCE.getAllParamInfo(
                typeCoercerFactory, ruleClass.getConstructorArgType());
        populateAttributes(kwargs, builder, allParamInfo);
        throwOnMissingRequiredAttribute(kwargs, allParamInfo);
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

  /**
   * Represents a {@code native} variable available in Skylark extension files. It's responsible for
   * handling calls like {@code native.java_library(...)} in {@code .bzl} files.
   */
  private static class NativeModule implements ClassObject, SkylarkValue {
    private final ImmutableMap<String, BuiltinFunction> buckRuleFunctionRegistry;

    private NativeModule(ImmutableList<BuiltinFunction> buckRuleFunctions) {
      ImmutableMap.Builder<String, BuiltinFunction> registryBuilder = ImmutableMap.builder();
      for (BuiltinFunction buckRuleFunction : buckRuleFunctions) {
        registryBuilder.put(buckRuleFunction.getName(), buckRuleFunction);
      }
      buckRuleFunctionRegistry = registryBuilder.build();
    }

    @Nullable
    @Override
    public BuiltinFunction getValue(String name) {
      return buckRuleFunctionRegistry.get(name);
    }

    @Override
    public ImmutableCollection<String> getKeys() {
      // TODO(ttsugrii): Remove this unnecessary copying once guava version in Skylark and Buck match
      return bazel.shaded.com.google.common.collect.ImmutableSet.copyOf(
          buckRuleFunctionRegistry.keySet());
    }

    @Nullable
    @Override
    public String errorMessage(String name) {
      String suffix =
          "Available attributes: " + Joiner.on(", ").join(Ordering.natural().sortedCopy(getKeys()));
      return "native object does not have an attribute " + name + "\n" + suffix;
    }

    @Override
    public void repr(SkylarkPrinter printer) {
      boolean first = true;
      printer.append("struct(");
      // Sort by key to ensure deterministic output.
      for (String key : Ordering.natural().sortedCopy(getKeys())) {
        if (!first) {
          printer.append(", ");
        }
        first = false;
        printer.append(key);
        printer.append(" = ");
        printer.repr(getValue(key));
      }
      printer.append(")");
    }

    @Override
    public boolean isImmutable() {
      return true;
    }

    @Override
    public int hashCode() {
      List<String> keys = new ArrayList<>(getKeys());
      Collections.sort(keys);
      List<Object> objectsToHash = new ArrayList<>();
      for (String key : keys) {
        objectsToHash.add(key);
        objectsToHash.add(getValue(key));
      }
      return Objects.hashCode(objectsToHash.toArray());
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof NativeModule)) {
        return false;
      }
      NativeModule other = (NativeModule) obj;
      return this == other || this.buckRuleFunctionRegistry.equals(other.buckRuleFunctionRegistry);
    }
  }

  /**
   * Tracks parse context.
   *
   * <p>This class provides API to record information retrieved while parsing a build file like
   * parsed rules.
   */
  private static class ParseContext {
    private final ImmutableList.Builder<Map<String, Object>> rawRuleBuilder;

    private ParseContext() {
      rawRuleBuilder = ImmutableList.builder();
    }

    private void recordRule(Map<String, Object> rawRule) {
      rawRuleBuilder.add(rawRule);
    }

    /**
     * @return The list of raw build rules discovered in parsed build file. Raw rule is presented as
     *     a map with attributes as keys and parameters as values.
     */
    ImmutableList<Map<String, Object>> getRecordedRules() {
      return rawRuleBuilder.build();
    }
  }
}
