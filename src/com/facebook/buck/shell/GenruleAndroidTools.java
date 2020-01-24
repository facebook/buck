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

package com.facebook.buck.shell;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidTools;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Immutable class for holding Android paths and tools, for use in {@link GenruleBuildable}.
 *
 * <p>Note that, despite implementing AddsToRuleKey, GenruleAndroidTools does not actually
 * contribute to rule keys. The reason for this is that all of the Path objects contained in this
 * object are paths outside of the repository. GenruleAndroidTools is never serialized due to other
 * logic in {@link GenruleBuildable} that prevents it from happening; it is a logic error to
 * serialize this class across a {@link com.facebook.buck.rules.modern.ModernBuildRule} boundary.
 *
 * <p>This class still must implement AddsToRuleKey because ModernBuildRule requires that all fields
 * in a {@link com.facebook.buck.rules.modern.Buildable} must implement AddsToRuleKey so that a
 * serializer can be derived.
 */
@BuckStyleValue
public abstract class GenruleAndroidTools implements AddsToRuleKey {
  public abstract Path getAndroidSdkLocation();

  public abstract Path getAndroidPathToDx();

  public abstract Path getAndroidPathToZipalign();

  public abstract Tool getAaptTool();

  public abstract Tool getAapt2Tool();

  public abstract Optional<Path> getAndroidNdkLocation();

  /**
   * Extracts a set of Android tools for a particular build using that build's target and rule
   * resolver. This is done here is instead of within the GenruleBuildable because Buildables don't
   * have access to rule resolvers, and one is used here to look up the Tool for aapt2. In general
   * Genrule probably shouldn't know about Android, but for the moment it does.
   *
   * @param tools Tools reference for Android
   * @param target The BuildTarget to retrieve tools for
   * @param ruleResolver Rule resolver for resolving tool referenves
   */
  public static GenruleAndroidTools of(
      AndroidTools tools, BuildTarget target, BuildRuleResolver ruleResolver) {
    Optional<AndroidNdk> androidNdk = tools.getAndroidNdk();
    AndroidPlatformTarget androidPlatformTarget = tools.getAndroidPlatformTarget();
    Path androidSdk = tools.getAndroidSdkLocation().getSdkRootPath();
    Path androidDx = androidPlatformTarget.getDxExecutable();
    Path androidZipalign = androidPlatformTarget.getZipalignExecutable();
    Tool androidAapt = androidPlatformTarget.getAaptExecutable().get();
    ToolProvider aapt2ToolProvider = androidPlatformTarget.getAapt2ToolProvider();
    TargetConfiguration targetConfiguration = target.getTargetConfiguration();
    Tool androidAapt2 = aapt2ToolProvider.resolve(ruleResolver, targetConfiguration);
    return ImmutableGenruleAndroidTools.of(
        androidSdk,
        androidDx,
        androidZipalign,
        androidAapt,
        androidAapt2,
        androidNdk.map(AndroidNdk::getNdkRootPath));
  }
}
