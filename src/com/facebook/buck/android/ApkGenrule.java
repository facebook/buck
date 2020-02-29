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

import com.facebook.buck.android.toolchain.AndroidTools;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasClasspathEntries;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryClasspathProvider;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.SourceSet;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.sandbox.SandboxProperties;
import com.facebook.buck.shell.BaseGenrule;
import com.facebook.buck.shell.GenruleAndroidTools;
import com.facebook.buck.shell.GenruleBuildable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A specialization of a genrule that specifically allows the modification of apks. This is useful
 * for processes that modify an APK, such as zipaligning it or signing it.
 *
 * <p>The generated APK will be at <code><em>rule_name</em>.apk</code>.
 *
 * <pre>
 * apk_genrule(
 *   name = 'fb4a_signed',
 *   apk = ':fbandroid_release'
 *   deps = [
 *     '//java/com/facebook/sign:fbsign_jar',
 *   ],
 *   cmd = '${//java/com/facebook/sign:fbsign_jar} --input $APK --output $OUT'
 * )
 * </pre>
 */
public final class ApkGenrule extends BaseGenrule<ApkGenrule.Buildable>
    implements HasInstallableApk, HasRuntimeDeps, HasClasspathEntries {

  private final HasInstallableApk hasInstallableApk;

  ApkGenrule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SandboxExecutionStrategy sandboxExecutionStrategy,
      BuildRuleResolver resolver,
      SourceSet srcs,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe,
      Optional<String> type,
      boolean isCacheable,
      Optional<String> environmentExpansionSeparator,
      Optional<AndroidTools> androidTools,
      HasInstallableApk installableApk) {
    super(
        buildTarget,
        projectFilesystem,
        resolver,
        new Buildable(
            buildTarget,
            projectFilesystem,
            sandboxExecutionStrategy,
            srcs,
            cmd,
            bash,
            cmdExe,
            type,
            /* out */ Optional.of(buildTarget.getShortNameAndFlavorPostfix() + ".apk"),
            Optional.empty(),
            false,
            isCacheable,
            environmentExpansionSeparator.orElse(" "),
            Optional.empty(),
            androidTools.map(tools -> GenruleAndroidTools.of(tools, buildTarget, resolver)),
            false,
            installableApk.getApkInfo().getApkPath()));
    // TODO(cjhopman): Disallow apk_genrule depending on an apk with exopackage enabled.
    this.hasInstallableApk = installableApk;
  }

  public HasInstallableApk getInstallableApk() {
    return hasInstallableApk;
  }

  @Override
  public ApkInfo getApkInfo() {
    return hasInstallableApk.getApkInfo().withApkPath(getSourcePathToOutput());
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(BuildRuleResolver buildRuleResolver) {
    return HasInstallableApkSupport.getRuntimeDepsForInstallableApk(this, buildRuleResolver);
  }

  @Override
  public ImmutableSet<SourcePath> getTransitiveClasspaths() {
    // This is used primarily for buck audit classpath.
    return JavaLibraryClasspathProvider.getClasspathsFromLibraries(getTransitiveClasspathDeps());
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return JavaLibraryClasspathProvider.getClasspathDeps(
        getBuildDeps().stream()
            .filter(rule -> rule instanceof HasInstallableApk)
            .collect(ImmutableList.toImmutableList()));
  }

  @Override
  public ImmutableSet<SourcePath> getImmediateClasspaths() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<SourcePath> getOutputClasspaths() {
    // The apk has no exported deps or classpath contributions of its own
    return ImmutableSet.of();
  }

  static class Buildable extends GenruleBuildable {
    @AddToRuleKey private final SourcePath apkPath;

    public Buildable(
        BuildTarget buildTarget,
        ProjectFilesystem filesystem,
        SandboxExecutionStrategy sandboxExecutionStrategy,
        SourceSet srcs,
        Optional<Arg> cmd,
        Optional<Arg> bash,
        Optional<Arg> cmdExe,
        Optional<String> type,
        Optional<String> out,
        Optional<ImmutableMap<OutputLabel, ImmutableSet<String>>> outs,
        boolean enableSandboxingInGenrule,
        boolean isCacheable,
        String environmentExpansionSeparator,
        Optional<SandboxProperties> sandboxProperties,
        Optional<GenruleAndroidTools> androidTools,
        boolean executeRemotely,
        SourcePath apkPath) {
      super(
          buildTarget,
          filesystem,
          sandboxExecutionStrategy,
          srcs,
          cmd,
          bash,
          cmdExe,
          type,
          out,
          outs,
          enableSandboxingInGenrule,
          isCacheable,
          environmentExpansionSeparator,
          sandboxProperties,
          androidTools,
          executeRemotely);
      this.apkPath = apkPath;
    }

    @Override
    public void addEnvironmentVariables(
        SourcePathResolverAdapter pathResolver,
        OutputPathResolver outputPathResolver,
        ProjectFilesystem filesystem,
        Path srcPath,
        Path tmpPath,
        ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
      super.addEnvironmentVariables(
          pathResolver,
          outputPathResolver,
          filesystem,
          srcPath,
          tmpPath,
          environmentVariablesBuilder);
      // We have to use an absolute path, because genrules are run in a temp directory.
      String apkAbsolutePath = pathResolver.getAbsolutePath(apkPath).toString();
      environmentVariablesBuilder.put("APK", apkAbsolutePath);
    }
  }
}
