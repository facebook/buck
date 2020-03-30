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

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import java.util.EnumSet;
import javax.annotation.Nullable;

/**
 * Buildable that is responsible for:
 *
 * <ul>
 *   <li>Bucketing pre-dexed jars into lists for primary and secondary dex files (if the app is
 *       split-dex).
 *   <li>Merging the pre-dexed jars into primary and secondary dex files.
 *   <li>Writing the split-dex "metadata.txt".
 * </ul>
 *
 * <p>Clients of this Buildable may need to know:
 *
 * <ul>
 *   <li>The locations of the zip files directories containing secondary dex files and metadata.
 * </ul>
 *
 * This uses a separate implementation from addDexingSteps. The differences in the splitting logic
 * are too significant to make it worth merging them.
 */
public abstract class PreDexMerge extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements HasDexFiles {

  /** Options to use with {@link DxStep} when merging pre-dexed files. */
  static final EnumSet<DxStep.Option> DX_MERGE_OPTIONS =
      EnumSet.of(
          DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
          DxStep.Option.RUN_IN_PROCESS,
          DxStep.Option.NO_DESUGAR,
          DxStep.Option.NO_OPTIMIZE);

  @AddToRuleKey final String dexTool;

  final AndroidPlatformTarget androidPlatformTarget;

  public PreDexMerge(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      AndroidPlatformTarget androidPlatformTarget,
      String dexTool) {
    super(buildTarget, projectFilesystem, params);
    this.androidPlatformTarget = androidPlatformTarget;
    this.dexTool = dexTool;
  }

  Path getPrimaryDexRoot() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s_output/primary");
  }

  Path getPrimaryDexPath() {
    return getPrimaryDexRoot().resolve("classes.dex");
  }

  public SourcePath getSourcePathToPrimaryDex() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPrimaryDexPath());
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }
}
