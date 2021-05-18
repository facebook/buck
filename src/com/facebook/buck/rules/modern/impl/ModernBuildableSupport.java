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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.common.RecordArtifactVerifier;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.DefaultOutputPathResolver;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;

/**
 * ModernBuildableSupport provides methods to make using, implementing and migrating to
 * ModernBuildRules easier.
 */
public class ModernBuildableSupport {
  // Not intended to be instantiated.
  private ModernBuildableSupport() {}

  /**
   * Derives an {@link RecordArtifactVerifier} from the {@link AddsToRuleKey} annotated fields of
   * {@link OutputPath} type.
   */
  public static RecordArtifactVerifier getDerivedArtifactVerifier(
      BuildTarget buildTarget, ProjectFilesystem filesystem, Buildable buildable) {
    return getDerivedArtifactVerifier(buildTarget, filesystem, buildable, path -> {});
  }

  private static RecordArtifactVerifier getDerivedArtifactVerifier(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Buildable buildable,
      BuildableContext buildableContext) {
    ImmutableSet.Builder<Path> allowedPathsBuilder = ImmutableSet.builder();
    ModernBuildRule.recordOutputs(
        allowedPathsBuilder::add, new DefaultOutputPathResolver(filesystem, target), buildable);
    return new RecordArtifactVerifier(allowedPathsBuilder.build(), buildableContext);
  }
}
