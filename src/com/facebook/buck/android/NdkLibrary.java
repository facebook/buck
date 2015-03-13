/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * An object that represents a collection of Android NDK source code.
 * <p>
 * Suppose this were a rule defined in <code>src/com/facebook/feed/jni/BUCK</code>:
 * <pre>
 * ndk_library(
 *   name = 'feed-jni',
 *   deps = [],
 *   flags = ["NDK_DEBUG=1", "V=1"],
 * )
 * </pre>
 */
public class NdkLibrary extends AbstractBuildRule
    implements NativeLibraryBuildRule, AndroidPackageable {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, LIBRARY);

  /** @see NativeLibraryBuildRule#isAsset() */
  private final boolean isAsset;

  /** The directory containing the Android.mk file to use. This value includes a trailing slash. */
  private final Path root;
  private final Path makefile;
  private final String lastPathComponent;
  private final Path buildArtifactsDirectory;
  private final Path genDirectory;

  private final ImmutableSortedSet<SourcePath> sources;
  private final ImmutableList<String> flags;
  private final Optional<String> ndkVersion;
  private final Function<String, String> macroExpander;

  protected NdkLibrary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Path makefile,
      Set<SourcePath> sources,
      List<String> flags,
      boolean isAsset,
      Optional<String> ndkVersion,
      Function<String, String> macroExpander) {
    super(params, resolver);
    this.isAsset = isAsset;

    BuildTarget buildTarget = params.getBuildTarget();
    this.root = buildTarget.getBasePath();
    this.makefile = Preconditions.checkNotNull(makefile);
    this.lastPathComponent = "__lib" + buildTarget.getShortNameAndFlavorPostfix();
    this.buildArtifactsDirectory = getBuildArtifactsDirectory(buildTarget, true /* isScratchDir */);
    this.genDirectory = getBuildArtifactsDirectory(buildTarget, false /* isScratchDir */);

    Preconditions.checkArgument(!sources.isEmpty(),
        "Must include at least one file (Android.mk?) in ndk_library rule");
    this.sources = ImmutableSortedSet.copyOf(sources);
    this.flags = ImmutableList.copyOf(flags);

    this.ndkVersion = ndkVersion;
    this.macroExpander = macroExpander;
  }

  @Override
  public boolean isAsset() {
    return isAsset;
  }

  @Override
  public Path getLibraryPath() {
    return genDirectory;
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    // An ndk_library() does not have a "primary output" at this time.
    return null;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {

    // .so files are written to the libs/ subdirectory of the output directory.
    // All of them should be recorded via the BuildableContext.
    Path binDirectory = buildArtifactsDirectory.resolve("libs");
    Step nkdBuildStep = new NdkBuildStep(
        root,
        makefile,
        buildArtifactsDirectory,
        binDirectory,
        flags,
        macroExpander);

    Step mkDirStep = new MakeCleanDirectoryStep(genDirectory);
    Step copyStep = CopyStep.forDirectory(
        binDirectory,
        genDirectory,
        CopyStep.DirectoryMode.CONTENTS_ONLY);

    buildableContext.recordArtifactsInDirectory(genDirectory);
    // Some tools need to inspect .so files whose symbols haven't been stripped, so cache these too.
    // However, the intermediate object files are huge and we have no interest in them, so filter
    // them out.
    Step cacheUnstrippedObjs = new AbstractExecutionStep("cache_unstripped_so") {
      @Override
      public int execute(ExecutionContext context) {
        try {
          Set<Path> unstrippedSharedObjs = context.getProjectFilesystem()
              .getFilesUnderPath(
                  buildArtifactsDirectory,
                  new Predicate<Path>() {
                    @Override
                    public boolean apply(Path input) {
                      return input.toString().endsWith(".so");
                    }
                  });
          for (Path path : unstrippedSharedObjs) {
            buildableContext.recordArtifact(path);
          }
        } catch (IOException e) {
          context.logError(e, "Failed to cache intermediate artifacts of %s.", getBuildTarget());
          return 1;
        }
        return 0;
      }
    };

    return ImmutableList.of(nkdBuildStep, mkDirStep, copyStep, cacheUnstrippedObjs);
  }

  /**
   * @param isScratchDir true if this should be the "working directory" where a build rule may write
   *     intermediate files when computing its output. false if this should be the gen/ directory
   *     where the "official" outputs of the build rule should be written. Files of the latter type
   *     can be referenced via a {@link com.facebook.buck.rules.BuildTargetSourcePath} or somesuch.
   */
  private Path getBuildArtifactsDirectory(BuildTarget target, boolean isScratchDir) {
    Path base = isScratchDir ? BuckConstant.SCRATCH_PATH : BuckConstant.GEN_PATH;
    return base.resolve(target.getBasePath()).resolve(lastPathComponent);
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("ndk_version", ndkVersion.or("NONE"))
        .setReflectively("flags", flags)
        .setReflectively("is_asset", isAsset());
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return getResolver().filterInputsToCompareToOutput(sources);
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(getDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    if (isAsset) {
      collector.addNativeLibAssetsDirectory(getLibraryPath());
    } else {
      collector.addNativeLibsDirectory(getBuildTarget(), getLibraryPath());
    }
  }
}
