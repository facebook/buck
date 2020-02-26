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

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.parser.LabelCache;
import com.facebook.buck.parser.api.BuildFileManifest;
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
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.hamcrest.Matchers;

public class SkylarkProjectBuildFileParserTestUtils {
  // A simple wrapper around skylark parser that records interesting events.
  static class RecordingParser extends SkylarkProjectBuildFileParser {
    Map<Path, Integer> readCounts;
    Map<Path, Integer> buildCounts;

    public RecordingParser(SkylarkProjectBuildFileParser delegate) {
      super(delegate);
      readCounts = new HashMap<>();
      buildCounts = new HashMap<>();
    }

    @Override
    public BuildFileAST readSkylarkAST(Path path, FileKind fileKind) throws IOException {
      readCounts.compute(path, (k, v) -> v == null ? 1 : v + 1);
      return super.readSkylarkAST(path, fileKind);
    }

    @Override
    public ExtensionData buildExtensionData(ExtensionLoadState load) throws InterruptedException {
      ExtensionData result = super.buildExtensionData(load);
      buildCounts.compute(result.getPath(), (k, v) -> v == null ? 1 : v + 1);
      return result;
    }

    public ImmutableMap<Path, Integer> expectedCounts(Object... args) {
      assert args.length % 2 == 0;
      ImmutableMap.Builder<Path, Integer> builder = ImmutableMap.builder();
      for (int i = 0; i < args.length; i += 2) {
        builder.put((Path) args[i], (Integer) args[i + 1]);
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
      SkylarkFilesystem skylarkFilesystem,
      EventHandler eventHandler,
      ProjectBuildFileParserOptions options,
      KnownRuleTypesProvider knownRuleTypesProvider,
      Cell cell) {
    return SkylarkProjectBuildFileParser.using(
        options,
        BuckEventBusForTests.newInstance(),
        skylarkFilesystem,
        BuckGlobals.of(
            SkylarkBuildModule.BUILD_MODULE,
            options.getDescriptions(),
            options.getUserDefinedRulesState(),
            options.getImplicitNativeRulesState(),
            new RuleFunctionFactory(new DefaultTypeCoercerFactory()),
            LabelCache.newLabelCache(),
            knownRuleTypesProvider.getUserDefinedRuleTypes(cell),
            options.getPerFeatureProviders()),
        eventHandler,
        NativeGlobber::create);
  }

  static Map<String, Object> getSingleRule(
      SkylarkProjectBuildFileParser parser, java.nio.file.Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    BuildFileManifest buildFileManifest = parser.getManifest(buildFile);
    assertThat(buildFileManifest.getTargets(), Matchers.aMapWithSize(1));
    return Iterables.getOnlyElement(buildFileManifest.getTargets().values());
  }
}
