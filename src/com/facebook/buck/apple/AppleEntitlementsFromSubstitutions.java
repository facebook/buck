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

package com.facebook.buck.apple;

import static com.facebook.buck.apple.AppleBundle.CODE_SIGN_ENTITLEMENTS;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FindAndReplaceStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * A BuildRule to be used to search and process Entitlements.plist file provided in Info.plist
 * substitutions map, result is used later during signing
 */
public class AppleEntitlementsFromSubstitutions
    extends ModernBuildRule<AppleEntitlementsFromSubstitutions> implements Buildable {

  public static final Flavor FLAVOR = InternalFlavor.of("apple-entitlements-from-substitutions");

  /**
   * Returns, if exists, an absolute path to entitlements file provided in Info.plist substitutions
   * map
   */
  public static Optional<AbsPath> entitlementsPathInSubstitutions(
      ProjectFilesystem filesystem,
      BuildTarget bundleBuildTarget,
      ApplePlatform platform,
      ImmutableMap<String, String> substitutions) {
    AbsPath srcRoot =
        filesystem
            .getRootPath()
            .resolve(
                bundleBuildTarget
                    .getCellRelativeBasePath()
                    .getPath()
                    .toPath(filesystem.getFileSystem()));
    Optional<String> maybeEntitlementsPathString =
        InfoPlistSubstitution.getVariableExpansionForPlatform(
            CODE_SIGN_ENTITLEMENTS,
            platform.getName(),
            InfoPlistSubstitution.variableExpansionWithDefaults(
                substitutions,
                ImmutableMap.of(
                    "SOURCE_ROOT", srcRoot.toString(),
                    "SRCROOT", srcRoot.toString())));
    return maybeEntitlementsPathString.map(pathString -> srcRoot.resolve(Paths.get(pathString)));
  }

  @AddToRuleKey private final ImmutableMap<String, String> substitutions;
  @AddToRuleKey private final OutputPath output;

  private final AbsPath entitlementsPath;

  public AppleEntitlementsFromSubstitutions(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableMap<String, String> substitutions,
      AbsPath entitlementsPath) {
    super(buildTarget, projectFilesystem, ruleFinder, AppleEntitlementsFromSubstitutions.class);
    this.substitutions = substitutions;
    output = new OutputPath("Entitlements.plist");
    this.entitlementsPath = entitlementsPath;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    return ImmutableList.of(
        new FindAndReplaceStep(
            filesystem,
            entitlementsPath,
            outputPathResolver.resolvePath(output).getPath(),
            InfoPlistSubstitution.createVariableExpansionFunction(substitutions)));
  }
}
