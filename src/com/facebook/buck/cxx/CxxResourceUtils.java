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

package com.facebook.buck.cxx;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.graph.ConsumingTraverser;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.isolatedsteps.common.RmIsolatedStep;
import com.facebook.buck.util.MoreMaps;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Consumer;

/** Utility class containing helpers for C++ resource handling. */
public class CxxResourceUtils {

  private CxxResourceUtils() {}

  /** @return a resource map with fully qualified names. */
  public static ImmutableMap<CxxResourceName, SourcePath> fullyQualify(
      BuildTarget target,
      Optional<String> headerNamespace,
      ImmutableMap<String, SourcePath> resources) {
    ForwardRelativePath basePath =
        headerNamespace.map(ForwardRelativePath::of).orElse(target.getBaseName().getPath());
    return MoreMaps.transformKeys(
        resources,
        name -> new CxxResourceName(basePath.resolve(PathFormatter.pathWithUnixSeparators(name))));
  }

  /** @return a map of all fully qualified resources from transitive dependencies. */
  public static ImmutableMap<CxxResourceName, SourcePath> gatherResources(
      ActionGraphBuilder graphBuilder,
      ImmutableMap<CxxResourceName, SourcePath> binaryResources,
      Iterable<CxxResourcesProvider> resourceDeps) {
    // Convert incoming map keyed by relative resource names to their fully qualified variants.
    LinkedHashMap<CxxResourceName, SourcePath> builder = new LinkedHashMap<>(binaryResources);
    ConsumingTraverser.breadthFirst(
            resourceDeps, (p, c) -> p.forEachCxxResourcesDep(graphBuilder, c))
        .forEach(
            p ->
                p.getCxxResources()
                    .forEach(
                        (name, path) -> {
                          SourcePath prev = builder.put(name, path);
                          if (prev != null && !prev.equals(path)) {
                            throw new HumanReadableException(
                                "Conflicting reosurces for name \"%s\": %s and %s",
                                name, prev, path);
                          }
                        }));
    return ImmutableMap.copyOf(builder);
  }

  /**
   * @return a {@link Consumer} which accepts {@link BuildTarget}s and filter-casts them {@link
   *     CxxResourcesProvider}s.
   */
  public static Consumer<BuildTarget> filterConsumer(
      BuildRuleResolver resolver, Consumer<? super CxxResourcesProvider> consumer) {
    return target -> {
      BuildRule rule = resolver.getRule(target);
      if (rule instanceof CxxResourcesProvider) {
        consumer.accept((CxxResourcesProvider) rule);
      }
    };
  }

  // Return a path that's consistently locatable from the binary output path.
  public static RelPath getResourcesFile(RelPath binary) {
    return MorePaths.appendSuffix(binary, ".resources.json");
  }

  /** Add {@link Step}s to create a JSON file for the given resources. */
  public static void addResourceSteps(
      SourcePathResolverAdapter resolver,
      BuildRule binary,
      ImmutableMap<CxxResourceName, SourcePath> resources,
      ImmutableCollection.Builder<Step> builder) {
    RelPath output = resolver.getCellUnsafeRelPath(binary.getSourcePathToOutput());
    RelPath resourcesFile = getResourcesFile(output);
    builder.add(RmIsolatedStep.of(resourcesFile));
    if (!resources.isEmpty()) {
      builder.add(
          new CxxResourcesStep(
              resourcesFile,
              resolver.getAbsolutePath(binary.getSourcePathToOutput()).getParent(),
              ImmutableMap.copyOf(
                  MoreMaps.transformKeys(
                      Maps.transformValues(resources, resolver::getAbsolutePath),
                      CxxResourceName::getNameAsPath))));
    }
  }
}
