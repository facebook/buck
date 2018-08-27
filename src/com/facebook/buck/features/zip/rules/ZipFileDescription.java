/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.features.zip.rules;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class ZipFileDescription
    implements DescriptionWithTargetGraph<ZipFileDescriptionArg>,
        VersionPropagator<ZipFileDescriptionArg> {

  @Override
  public Class<ZipFileDescriptionArg> getConstructorArgType() {
    return ZipFileDescriptionArg.class;
  }

  @Override
  public Zip createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ZipFileDescriptionArg args) {

    ImmutableSortedSet<SourcePath> zipSources = args.getZipSrcs();
    Optional<Boolean> mergeSourceZips = args.getMergeSourceZips();

    if (!zipSources.isEmpty() && mergeSourceZips.isPresent())
      throw new IllegalArgumentException(
          "Illegal to define merge_source_zips when zip_srcs is present in " + buildTarget);

    return new Zip(
        new SourcePathRuleFinder(context.getActionGraphBuilder()),
        buildTarget,
        context.getProjectFilesystem(),
        args.getOut(),
        args.getSrcs(),
        zipSources,
        args.getFlatten(),
        mergeSourceZips);
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractZipFileDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs {
    @Value.Default
    default String getOut() {
      return getName() + ".zip";
    }

    @Value.Default
    default boolean getFlatten() {
      return false;
    }

    @Value.Default
    default Optional<Boolean> getMergeSourceZips() {
      return Optional.empty();
    }

    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getZipSrcs();
  }
}
