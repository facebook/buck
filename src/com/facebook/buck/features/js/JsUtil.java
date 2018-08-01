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

package com.facebook.buck.features.js;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.ProxyArg;
import com.facebook.buck.rules.macros.AbstractMacroExpanderWithoutPrecomputedWork;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.shell.WorkerShellStep;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.util.json.JsonBuilder;
import com.facebook.buck.util.json.JsonBuilder.ObjectBuilder;
import com.facebook.buck.worker.WorkerJobParams;
import com.facebook.buck.worker.WorkerProcessIdentity;
import com.facebook.buck.worker.WorkerProcessParams;
import com.facebook.buck.worker.WorkerProcessPoolFactory;
import com.fasterxml.jackson.core.io.CharTypes;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public class JsUtil {
  private static final ImmutableList<AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro>>
      MACRO_EXPANDERS =
          ImmutableList.of(
              /**
               * Expands JSON with macros, escaping macro values for interpolation into quoted
               * strings.
               */
              new LocationMacroExpander() {
                @Override
                protected Arg expand(
                    SourcePathResolver resolver, LocationMacro macro, BuildRule rule)
                    throws MacroException {
                  return new ProxyArg(super.expand(resolver, macro, rule)) {
                    @Override
                    public void appendToCommandLine(
                        Consumer<String> consumer, SourcePathResolver pathResolver) {
                      super.appendToCommandLine(
                          s -> consumer.accept(escapeJsonForStringEmbedding(s)), pathResolver);
                    }
                  };
                }
              });
  private static final int[] outputEscapes = CharTypes.get7BitOutputEscapes();

  private JsUtil() {}

  static WorkerShellStep jsonWorkerShellStepAddingFlavors(
      WorkerTool worker,
      ObjectBuilder jobArgs,
      BuildTarget buildTarget,
      SourcePathResolver pathResolver,
      ProjectFilesystem filesystem) {
    String jobArgsString =
        jobArgs
            .addArray(
                "flavors",
                buildTarget
                    .getFlavors()
                    .stream()
                    .filter(JsFlavors::shouldBePassedToWorker)
                    .map(Flavor::getName)
                    .collect(JsonBuilder.toArrayOfStrings()))
            .toString();
    Tool tool = worker.getTool();
    WorkerJobParams params =
        WorkerJobParams.of(
            jobArgsString,
            WorkerProcessParams.of(
                worker.getTempDir(),
                tool.getCommandPrefix(pathResolver),
                tool.getEnvironment(pathResolver),
                worker.getMaxWorkers(),
                worker.isPersistent()
                    ? Optional.of(
                        WorkerProcessIdentity.of(
                            buildTarget.getCellPath().toString() + buildTarget,
                            worker.getInstanceKey()))
                    : Optional.empty()));
    return new WorkerShellStep(
        buildTarget,
        Optional.of(params),
        Optional.empty(),
        Optional.empty(),
        new WorkerProcessPoolFactory(filesystem));
  }

  static boolean isJsLibraryTarget(BuildTarget target, TargetGraph targetGraph) {
    return targetGraph.get(target).getDescription() instanceof JsLibraryDescription;
  }

  static BuildRuleParams paramsWithDeps(BuildRuleParams params, BuildRule... rules) {
    return params.withoutDeclaredDeps().withExtraDeps(ImmutableSortedSet.copyOf(rules));
  }

  static SourcePath relativeToOutputRoot(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem, String subpath) {
    return ExplicitBuildTargetSourcePath.of(
        buildTarget,
        BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s").resolve(subpath));
  }

  public static String getValueForFlavor(ImmutableMap<UserFlavor, String> map, Flavor flavor) {
    return Preconditions.checkNotNull(map.get(flavor), "no string representation of the flavor");
  }

  private static final ImmutableMap<UserFlavor, String> PLATFORM_STRINGS =
      ImmutableMap.of(
          JsFlavors.ANDROID, "android",
          JsFlavors.IOS, "ios");

  static Optional<String> getPlatformString(Set<Flavor> flavors) {
    return JsFlavors.PLATFORM_DOMAIN
        .getFlavor(flavors)
        .map(platform -> getValueForFlavor(PLATFORM_STRINGS, platform));
  }

  public static String getSourcemapPath(JsBundleOutputs jsBundleOutputs) {
    return String.format("map/%s.map", jsBundleOutputs.getBundleName());
  }

  /**
   * Wraps the {@link com.facebook.buck.rules.macros.StringWithMacros} coming from {@link
   * HasExtraJson} so that it can be added to rule keys and expanded easily.
   */
  public static Optional<Arg> getExtraJson(
      HasExtraJson args,
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots) {
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(target, cellRoots, MACRO_EXPANDERS);
    return args.getExtraJson().map(x -> macrosConverter.convert(x, graphBuilder));
  }

  /** @return The input with all special JSON characters escaped, but not wrapped in quotes. */
  public static String escapeJsonForStringEmbedding(String input) {
    StringBuilder builder = new StringBuilder(input.length());
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (c > 0x7f || outputEscapes[c] == 0) {
        builder.append(c);
      } else if (outputEscapes[c] == -1) {
        builder.append('\\').append('u').append('0').append('0');
        if (c < 0x10) {
          builder.append('0');
        }
        builder.append(Integer.toHexString(c));
      } else {
        builder.append('\\').append((char) outputEscapes[c]);
      }
    }

    return builder.toString();
  }
}
