/*
 * Copyright 2015-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.js;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.js.AbstractReactNativeLibraryDescription.Platform;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

public class ReactNativeLibrary extends AbstractBuildRule
    implements AbiRule, AndroidPackageable {

  @AddToRuleKey
  private final SourcePath entryPath;

  @AddToRuleKey
  private final boolean isDevMode;

  @AddToRuleKey
  private final SourcePath jsPackager;

  @AddToRuleKey
  private final Platform platform;

  private final ReactNativeDeps depsFinder;
  private final Path output;

  protected ReactNativeLibrary(
      BuildRuleParams ruleParams,
      SourcePathResolver resolver,
      SourcePath entryPath,
      boolean isDevMode,
      String bundleName,
      SourcePath jsPackager,
      Platform platform,
      ReactNativeDeps depsFinder) {
    super(ruleParams, resolver);
    this.entryPath = entryPath;
    this.isDevMode = isDevMode;
    this.jsPackager = jsPackager;
    this.platform = platform;
    this.depsFinder = depsFinder;
    this.output = BuildTargets.getGenPath(ruleParams.getBuildTarget(), "__%s/").resolve(bundleName);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(new MakeCleanDirectoryStep(output.getParent()));

    steps.add(new ShellStep() {
      @Override
      public String getShortName() {
        return "bundle_react_native";
      }

      @Override
      protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
        ProjectFilesystem filesystem = context.getProjectFilesystem();
        return ImmutableList.of(
            getResolver().getPath(jsPackager).toString(),
            "bundle",
            "--entry-file", filesystem.resolve(getResolver().getPath(entryPath)).toString(),
            "--platform", platform.toString(),
            "--dev", isDevMode ? "true" : "false",
            "--bundle-output", filesystem.resolve(output).toString());
      }
    });
    buildableContext.recordArtifact(output);
    return steps.build();
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    return depsFinder.getInputsHash();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    // TODO(natthu): Consider defining two build rules, and make only the Android build rule
    // implement AndroidPackageable.
    Preconditions.checkState(platform == Platform.ANDROID);
    return AndroidPackageableCollector.getPackageableRules(getDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    Preconditions.checkState(platform == Platform.ANDROID);
    collector.addAssetsDirectory(
        getBuildTarget(),
        new PathSourcePath(getProjectFilesystem(), output.getParent()));
  }
}
