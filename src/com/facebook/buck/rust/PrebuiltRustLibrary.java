/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rust;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class PrebuiltRustLibrary extends AbstractBuildRule
  implements RustLinkable {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  @AddToRuleKey
  private final SourcePath rlib;
  @AddToRuleKey
  private final String crate;

  public PrebuiltRustLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath rlib,
      Optional<String> crate) {
    super(params, resolver);
    this.rlib = rlib;
    this.crate = crate.or(getBuildTarget().getShortName());
  }

  @Override
  public final ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public Path getPathToOutput() {
    return getLinkPath();
  }

  @Override
  public String getLinkTarget() {
    return crate;
  }

  private Path getLibDirPath() {
    return getLinkPath().getParent();
  }

  @Override
  public Path getLinkPath() {
    return getResolver().getRelativePath(rlib);
  }

  @Override
  public ImmutableSortedSet<Path> getDependencyPaths() {
    ImmutableSortedSet.Builder<Path> builder = ImmutableSortedSet.<Path>naturalOrder();

    builder.add(getLibDirPath());

    for (BuildRule rule: getDeps()) {
      if (rule instanceof RustLinkable) {
        builder.addAll(((RustLinkable) rule).getDependencyPaths());
      }
    }

    return builder.build();
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }
}
