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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.HasOutputName;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSortedMap;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractResourcesParameters implements AddsToRuleKey {
  @Value.Default
  @AddToRuleKey
  public ImmutableSortedMap<String, SourcePath> getResources() {
    return ImmutableSortedMap.of();
  }

  @Value.Default
  @AddToRuleKey
  public Optional<String> getResourcesRoot() {
    return Optional.empty();
  }

  public static ResourcesParameters of() {
    return ResourcesParameters.builder().build();
  }

  public static ResourcesParameters create(
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableCollection<SourcePath> resources,
      Optional<Path> resourcesRoot) {
    return ResourcesParameters.builder()
        .setResources(
            getNamedResources(
                DefaultSourcePathResolver.from(ruleFinder),
                ruleFinder,
                projectFilesystem,
                resources))
        .setResourcesRoot(resourcesRoot.map(Path::toString))
        .build();
  }

  public static ImmutableSortedMap<String, SourcePath> getNamedResources(
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem filesystem,
      ImmutableCollection<SourcePath> resources) {
    ImmutableSortedMap.Builder<String, SourcePath> builder = ImmutableSortedMap.naturalOrder();
    for (SourcePath rawResource : resources) {
      // If the path to the file defining this rule were:
      // "first-party/orca/lib-http/tests/com/facebook/orca/BUCK"
      //
      // And the value of resource were:
      // "first-party/orca/lib-http/tests/com/facebook/orca/protocol/base/batch_exception1.txt"
      //
      // Assuming that `src_roots = tests` were in the [java] section of the .buckconfig file,
      // then javaPackageAsPath would be:
      // "com/facebook/orca/protocol/base/"
      //
      // And the path that we would want to copy to the classes directory would be:
      // "com/facebook/orca/protocol/base/batch_exception1.txt"
      //
      // Therefore, some path-wrangling is required to produce the correct string.

      Optional<BuildRule> underlyingRule = ruleFinder.getRule(rawResource);
      Path relativePathToResource = pathResolver.getRelativePath(rawResource);

      String resource;

      if (underlyingRule.isPresent()) {
        BuildTarget underlyingTarget = underlyingRule.get().getBuildTarget();
        if (underlyingRule.get() instanceof HasOutputName) {
          resource =
              MorePaths.pathWithUnixSeparators(
                  underlyingTarget
                      .getBasePath()
                      .resolve(((HasOutputName) underlyingRule.get()).getOutputName()));
        } else {
          Path genOutputParent =
              BuildTargetPaths.getGenPath(filesystem, underlyingTarget, "%s").getParent();
          Path scratchOutputParent =
              BuildTargetPaths.getScratchPath(filesystem, underlyingTarget, "%s").getParent();
          Optional<Path> outputPath =
              MorePaths.stripPrefix(relativePathToResource, genOutputParent)
                  .map(Optional::of)
                  .orElse(MorePaths.stripPrefix(relativePathToResource, scratchOutputParent));
          Preconditions.checkState(
              outputPath.isPresent(),
              "%s is used as a resource but does not output to a default output directory",
              underlyingTarget.getFullyQualifiedName());
          resource =
              MorePaths.pathWithUnixSeparators(
                  underlyingTarget.getBasePath().resolve(outputPath.get()));
        }
      } else {
        resource = MorePaths.pathWithUnixSeparators(relativePathToResource);
      }
      builder.put(resource, rawResource);
    }
    return builder.build();
  }
}
