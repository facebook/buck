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

package com.facebook.buck.json;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.Description;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.vfs.FileSystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parser for build files written using Skylark syntax.
 *
 * <p>NOTE: This parser is work in progress and does not support many functions provided by Python
 * DSL parser like {@code read_config} and {@code include_defs} (currently implemented as a noop),
 * so DO NOT USE it production.
 */
public class SkylarkProjectBuildFileParser implements ProjectBuildFileParser {

  private static final Logger LOG = Logger.get(SkylarkProjectBuildFileParser.class);

  private static final BuiltinFunction INCLUDE_DEFS =
      new BuiltinFunction(
          "include_defs", FunctionSignature.POSITIONALS, BuiltinFunction.USE_AST_ENV) {
        @SuppressWarnings({"unused"})
        public Object invoke(SkylarkList<String> includes, FuncallExpression ast, Environment env) {
          // TODO(ttsugrii): consider implementing. Unfortunately include_defs function is fundamentally
          // lossy because it does not require all imported globals to be explicit, which makes
          // automatic conversion hard/impossible
          return Runtime.NONE;
        }
      };

  private final FileSystem fileSystem;
  private final ProjectBuildFileParserOptions options;
  private final BuckEventBus buckEventBus;

  private SkylarkProjectBuildFileParser(
      final ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem) {
    this.options = options;
    this.buckEventBus = buckEventBus;
    this.fileSystem = fileSystem;
  }

  public static SkylarkProjectBuildFileParser using(
      final ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem) {
    return new SkylarkProjectBuildFileParser(options, buckEventBus, fileSystem);
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
  public ImmutableList<Map<String, Object>> parseBuildRules(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    // TODO(ttsugrii): consider using a less verbose event handler. Also fancy handler can be
    // configured for terminals that support it.
    PrintingEventHandler eventHandler = new PrintingEventHandler(EnumSet.allOf(EventKind.class));
    BuildFileAST buildFileAst =
        BuildFileAST.parseBuildFile(
            ParserInputSource.create(fileSystem.getPath(buildFile.toString())), eventHandler);
    ImmutableList.Builder<Map<String, Object>> builder = ImmutableList.builder();
    try (Mutability mutability = Mutability.create("BUCK")) {
      Environment env = Environment.builder(mutability).build();
      env.setup("include_defs", INCLUDE_DEFS);
      setupBuckRules(buildFile, builder, env);
      boolean exec = buildFileAst.exec(env, eventHandler);
      if (!exec) {
        throw BuildFileParseException.createForUnknownParseError("Cannot parse build file");
      }
      return builder.build();
    }
  }

  /**
   * Sets up native Buck rules in Skylark environment.
   *
   * <p>This makes Buck rules like {@code java_library} available in build files.
   */
  private void setupBuckRules(
      Path buildFile, ImmutableList.Builder<Map<String, Object>> builder, Environment env) {
    for (Description<?> description : options.getDescriptions()) {
      String ruleClass = Description.getBuildRuleType(description).getName();
      String basePath = options.getProjectRoot().relativize(buildFile).getParent().toString();
      env.setup(ruleClass, newRuleDefinition(ruleClass, basePath, builder));
    }
  }

  /**
   * Create a Skylark definition for the {@code ruleClass} rule.
   *
   * <p>This makes functions like @{code java_library} available in build files. All they do is
   * capture passed attribute values in a map and adds them to the {@code ruleRegistry}.
   *
   * @param ruleClass The name of the rule to to define.
   * @param basePath The base path of the build file.
   * @param ruleRegistry The registry of invoked rules with corresponding values.
   * @return Skylark function to handle the Buck rule.
   */
  private BuiltinFunction newRuleDefinition(
      String ruleClass, String basePath, ImmutableList.Builder<Map<String, Object>> ruleRegistry) {
    return new BuiltinFunction(
        ruleClass, FunctionSignature.KWARGS, BuiltinFunction.USE_AST_ENV, /*isRule=*/ true) {

      @SuppressWarnings({"unused"})
      public Runtime.NoneType invoke(
          Map<String, Object> kwargs, FuncallExpression ast, Environment env)
          throws EvalException, InterruptedException {
        ruleRegistry.add(
            ImmutableMap.<String, Object>builder()
                .putAll(kwargs)
                .put("buck.base_path", basePath)
                .put("buck.type", ruleClass)
                .build());
        return Runtime.NONE;
      }
    };
  }

  @Override
  public void reportProfile() throws IOException {
    // TODO(ttsugrii): implement
  }

  @Override
  public void close() throws BuildFileParseException, InterruptedException, IOException {
    // nothing to do
  }
}
