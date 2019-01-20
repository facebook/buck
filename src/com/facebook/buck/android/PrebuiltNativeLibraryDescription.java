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

package com.facebook.buck.android;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Path;
import org.immutables.value.Value;

public class PrebuiltNativeLibraryDescription
    implements DescriptionWithTargetGraph<PrebuiltNativeLibraryDescriptionArg> {

  @Override
  public Class<PrebuiltNativeLibraryDescriptionArg> getConstructorArgType() {
    return PrebuiltNativeLibraryDescriptionArg.class;
  }

  @Override
  public PrebuiltNativeLibrary createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PrebuiltNativeLibraryDescriptionArg args) {
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    ImmutableSortedSet<? extends SourcePath> librarySources;
    try {
      librarySources =
          FluentIterable.from(projectFilesystem.getFilesUnderPath(args.getNativeLibs()))
              .transform(p -> PathSourcePath.of(projectFilesystem, p))
              .toSortedSet(Ordering.natural());
    } catch (IOException e) {
      throw new HumanReadableException(e, "Error traversing directory %s.", args.getNativeLibs());
    }

    return new PrebuiltNativeLibrary(
        buildTarget,
        projectFilesystem,
        params,
        args.getNativeLibs(),
        args.getIsAsset(),
        librarySources);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPrebuiltNativeLibraryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps {
    @Value.Default
    default boolean getIsAsset() {
      return false;
    }

    Path getNativeLibs();
  }
}
