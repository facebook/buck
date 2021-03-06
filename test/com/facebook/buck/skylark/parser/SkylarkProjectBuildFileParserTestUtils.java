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

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.starlark.eventhandler.EventHandler;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.skylark.function.SkylarkBuildModule;
import com.facebook.buck.skylark.io.impl.NativeGlobber;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.starlark.java.syntax.StarlarkFile;
import org.hamcrest.Matchers;

public class SkylarkProjectBuildFileParserTestUtils {
  // A simple wrapper around skylark parser that records interesting events.
  static class RecordingParser extends SkylarkProjectBuildFileParser {
    final Map<AbsPath, Integer> readCounts;
    final Map<AbsPath, Integer> buildCounts;

    public RecordingParser(SkylarkProjectBuildFileParser delegate) {
      super(delegate);
      readCounts = new HashMap<>();
      buildCounts = new HashMap<>();
    }

    @Override
    public StarlarkFile readSkylarkAST(AbsPath path) throws IOException {
      readCounts.compute(path, (k, v) -> v == null ? 1 : v + 1);
      return super.readSkylarkAST(path);
    }

    @Override
    public ExtensionData buildExtensionData(ExtensionLoadState load) throws InterruptedException {
      ExtensionData result = super.buildExtensionData(load);
      buildCounts.compute(result.getPath(), (k, v) -> v == null ? 1 : v + 1);
      return result;
    }

    public ImmutableMap<AbsPath, Integer> expectedCounts(Object... args) {
      assert args.length % 2 == 0;
      ImmutableMap.Builder<AbsPath, Integer> builder = ImmutableMap.builder();
      for (int i = 0; i < args.length; i += 2) {
        builder.put((AbsPath) args[i], (Integer) args[i + 1]);
      }
      return builder.build();
    }
  }

  static ProjectBuildFileParserOptions.Builder getDefaultParserOptions(
      Cell cell, KnownRuleTypesProvider knownRuleTypesProvider) {
    return ProjectBuildFileParserOptions.builder()
        .setProjectRoot(cell.getRoot())
        .setAllowEmptyGlobs(ParserConfig.DEFAULT_ALLOW_EMPTY_GLOBS)
        .setIgnorePaths(ImmutableSet.of())
        .setBuildFileName("BUCK")
        .setRawConfig(ImmutableMap.of("dummy_section", ImmutableMap.of("dummy_key", "dummy_value")))
        .setDescriptions(knownRuleTypesProvider.getNativeRuleTypes(cell).getDescriptions())
        .setPerFeatureProviders(
            knownRuleTypesProvider.getNativeRuleTypes(cell).getPerFeatureProviders())
        .setBuildFileImportWhitelist(ImmutableList.of())
        .setPythonInterpreter("skylark");
  }

  public static SkylarkProjectBuildFileParser createParserWithOptions(
      EventHandler eventHandler,
      ProjectBuildFileParserOptions options,
      KnownRuleTypesProvider knownRuleTypesProvider,
      Cell cell) {
    return SkylarkProjectBuildFileParser.using(
        options,
        BuckEventBusForTests.newInstance(),
        BuckGlobals.of(
            SkylarkBuildModule.BUILD_MODULE,
            options.getDescriptions(),
            options.getUserDefinedRulesState(),
            options.getImplicitNativeRulesState(),
            new RuleFunctionFactory(new DefaultTypeCoercerFactory()),
            knownRuleTypesProvider.getUserDefinedRuleTypes(cell),
            options.getPerFeatureProviders()),
        eventHandler,
        NativeGlobber.Factory.INSTANCE);
  }

  static RawTargetNode getSingleRule(SkylarkProjectBuildFileParser parser, AbsPath buildFile)
      throws IOException, InterruptedException {
    BuildFileManifest buildFileManifest = parser.getManifest(buildFile);
    assertThat(buildFileManifest.getTargets(), Matchers.aMapWithSize(1));
    return Iterables.getOnlyElement(buildFileManifest.getTargets().values());
  }

  static RawTargetNode getSingleRule(
      SkylarkProjectBuildFileParser parser, java.nio.file.Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    return getSingleRule(parser, AbsPath.of(buildFile));
  }
}
