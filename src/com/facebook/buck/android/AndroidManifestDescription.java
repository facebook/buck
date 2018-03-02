/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSortedSet;
import org.immutables.value.Value;

public class AndroidManifestDescription implements Description<AndroidManifestDescriptionArg> {

  private final AndroidManifestFactory androidManifestFactory;

  public AndroidManifestDescription(AndroidManifestFactory androidManifestFactory) {
    this.androidManifestFactory = androidManifestFactory;
  }

  @Override
  public Class<AndroidManifestDescriptionArg> getConstructorArgType() {
    return AndroidManifestDescriptionArg.class;
  }

  @Override
  public AndroidManifest createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidManifestDescriptionArg args) {
    return androidManifestFactory.createBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        context.getBuildRuleResolver(),
        args.getDeps(),
        args.getSkeleton());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAndroidManifestDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    SourcePath getSkeleton();

    /**
     * A collection of dependencies that includes android_library rules. The manifest files of the
     * android_library rules will be filtered out to become dependent source files for the {@link
     * AndroidManifest}.
     */
    @Override
    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getDeps();
  }
}
