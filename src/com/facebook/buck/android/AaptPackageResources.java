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

import com.facebook.buck.android.AndroidBinaryRule.PackageType;
import com.facebook.buck.android.AndroidBinaryRule.TargetCpuType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MorePaths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Packages the resources using {@code aapt}.
 */
public class AaptPackageResources extends AbstractBuildable {

  private final BuildTarget buildTarget;
  private final SourcePath manifest;
  private final UberRDotJava uberRDotJava;
  private final PackageType packageType;
  private final ImmutableSet<TargetCpuType> cpuFilters;

  AaptPackageResources(BuildTarget buildTarget,
      SourcePath manifest,
      UberRDotJava uberRDotJava,
      PackageType packageType,
      ImmutableSet<TargetCpuType> cpuFilters) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.manifest = Preconditions.checkNotNull(manifest);
    this.uberRDotJava = Preconditions.checkNotNull(uberRDotJava);
    this.packageType = Preconditions.checkNotNull(packageType);
    this.cpuFilters = Preconditions.checkNotNull(cpuFilters);
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(Collections.singleton(manifest));
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder
        .set("packageType", packageType.toString())
        .set("cpuFilters", ImmutableSortedSet.copyOf(cpuFilters).toString());
  }

  @Override
  public Path getPathToOutputFile() {
    return getResourceApkPath();
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Symlink the manifest to a path named AndroidManifest.xml. Do this before running any other
    // commands to ensure that it is available at the desired path.
    steps.add(new MkdirAndSymlinkFileStep(manifest.resolve(context), getAndroidManifestXml()));

    final AndroidTransitiveDependencies transitiveDependencies = uberRDotJava
        .getAndroidTransitiveDependencies();

    // If the strings should be stored as assets, then we need to create the .fbstr bundles.
    final ImmutableSet<String> resDirectories = uberRDotJava.getResDirectories();
    if (!resDirectories.isEmpty() && isStoreStringsAsAssets()) {
      Path tmpStringsDirPath = getPathForTmpStringAssetsDirectory();
      steps.add(new MakeCleanDirectoryStep(tmpStringsDirPath));
      steps.add(new CompileStringsStep(
          uberRDotJava.getNonEnglishStringFiles(),
          uberRDotJava.getPathToGeneratedRDotJavaSrcFiles(),
          tmpStringsDirPath));
    }

    // Copy the transitive closure of files in assets to a single directory, if any.
    // TODO(mbolin): Older versions of aapt did not support multiple -A flags, so we can probably
    // eliminate this now.
    Step collectAssets = new Step() {
      @Override
      public int execute(ExecutionContext context) {
        // This must be done in a Command because the files and directories that are specified may
        // not exist at the time this Command is created because the previous Commands have not run
        // yet.
        ImmutableList.Builder<Step> commands = ImmutableList.builder();
        try {
          createAllAssetsDirectory(
              transitiveDependencies.assetsDirectories,
              commands,
              new DefaultDirectoryTraverser());
        } catch (IOException e) {
          context.logError(e, "Error creating all assets directory in %s.", buildTarget);
          return 1;
        }

        for (Step command : commands.build()) {
          int exitCode = command.execute(context);
          if (exitCode != 0) {
            throw new HumanReadableException("Error running " + command.getDescription(context));
          }
        }

        return 0;
      }

      @Override
      public String getShortName() {
        return "symlink_assets";
      }

      @Override
      public String getDescription(ExecutionContext context) {
        return getShortName();
      }
    };
    steps.add(collectAssets);

    Optional<Path> assetsDirectory;
    if (transitiveDependencies.assetsDirectories.isEmpty()
        && transitiveDependencies.nativeLibAssetsDirectories.isEmpty()
        && !isStoreStringsAsAssets()) {
      assetsDirectory = Optional.absent();
    } else {
      assetsDirectory = Optional.of(getPathToAllAssetsDirectory());
    }

    if (!transitiveDependencies.nativeLibAssetsDirectories.isEmpty()) {
      Path nativeLibAssetsDir = assetsDirectory.get().resolve("lib");
      steps.add(new MakeCleanDirectoryStep(nativeLibAssetsDir));
      for (Path nativeLibDir : transitiveDependencies.nativeLibAssetsDirectories) {
        AndroidBinaryRule.copyNativeLibrary(nativeLibDir, nativeLibAssetsDir, cpuFilters, steps);
      }
    }

    if (isStoreStringsAsAssets()) {
      Path stringAssetsDir = assetsDirectory.get().resolve("strings");
      steps.add(new MakeCleanDirectoryStep(stringAssetsDir));
      steps.add(CopyStep.forDirectory(
          getPathForTmpStringAssetsDirectory(),
          stringAssetsDir,
          CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

    steps.add(new MkdirStep(getResourceApkPath().getParent()));

    steps.add(new AaptStep(
        getAndroidManifestXml(),
        resDirectories,
        assetsDirectory,
        getResourceApkPath(),
        packageType.isCrunchPngFiles()));

    buildableContext.recordArtifact(getAndroidManifestXml());
    buildableContext.recordArtifact(getResourceApkPath());
    return steps.build();
  }

  /**
   * Buck does not require the manifest to be named AndroidManifest.xml, but commands such as aapt
   * do. For this reason, we symlink the path to {@link #manifest} to the path returned by
   * this method, whose name is always "AndroidManifest.xml".
   * <p>
   * Therefore, commands created by this buildable should use this method instead of
   * {@link #manifest}.
   */
  Path getAndroidManifestXml() {
    return BuildTargets.getBinPath(buildTarget, "__manifest_%s__/AndroidManifest.xml");
  }

  private boolean isStoreStringsAsAssets() {
    return uberRDotJava.isStoreStringsAsAssets();
  }

  /**
   * Given a set of assets directories to include in the APK (which may be empty), return the path
   * to the directory that contains the union of all the assets. If any work needs to be done to
   * create such a directory, the appropriate commands should be added to the {@code commands}
   * list builder.
   * <p>
   * If there are no assets (i.e., {@code assetsDirectories} is empty), then the return value will
   * be an empty {@link Optional}.
   */
  @VisibleForTesting
  Optional<Path> createAllAssetsDirectory(
      Set<Path> assetsDirectories,
      ImmutableList.Builder<Step> steps,
      DirectoryTraverser traverser) throws IOException {
    if (assetsDirectories.isEmpty()) {
      return Optional.absent();
    }

    // Due to a limitation of aapt, only one assets directory can be specified, so if multiple are
    // specified in Buck, then all of the contents must be symlinked to a single directory.
    Path destination = getPathToAllAssetsDirectory();
    steps.add(new MakeCleanDirectoryStep(destination));
    final ImmutableMap.Builder<String, File> allAssets = ImmutableMap.builder();

    File destinationDirectory = destination.toFile();
    for (Path assetsDirectory : assetsDirectories) {
      traverser.traverse(new DirectoryTraversal(assetsDirectory.toFile()) {
        @Override
        public void visit(File file, String relativePath) {
          allAssets.put(relativePath, file);
        }
      });
    }

    for (Map.Entry<String, File> entry : allAssets.build().entrySet()) {
      steps.add(new MkdirAndSymlinkFileStep(
          MorePaths.newPathInstance(entry.getValue()),
          MorePaths.newPathInstance(destinationDirectory + "/" + entry.getKey())));
    }

    return Optional.of(destination);
  }

  /**
   * @return Path to the unsigned APK generated by this {@link Buildable}.
   */
  public Path getResourceApkPath() {
    return BuildTargets.getGenPath(buildTarget, "%s.unsigned.ap_");
  }

  @VisibleForTesting
  Path getPathToAllAssetsDirectory() {
    return BuildTargets.getBinPath(buildTarget, "__assets_%s__");
  }

  private Path getPathForTmpStringAssetsDirectory() {
    return BuildTargets.getBinPath(buildTarget, "__strings_%s__");
  }

  public static Builder newAaptPackageResourcesBuildableBuilder(
      AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  static class Builder extends AbstractBuildable.Builder {

    @Nullable private SourcePath manifest;
    @Nullable private UberRDotJava uberRDotJava;
    @Nullable private PackageType packageType;
    @Nullable private ImmutableSet<TargetCpuType> cpuFilters;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    protected BuildRuleType getType() {
      return BuildRuleType._AAPT_PACKAGE;
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    public Builder setAllParams(
        SourcePath manifest,
        UberRDotJava uberRDotJava,
        ImmutableSet<BuildTarget> nativeTargetsWithAssets,
        PackageType packageType,
        ImmutableSet<TargetCpuType> cpuFilters) {
      this.manifest = manifest;
      this.uberRDotJava = uberRDotJava;
      this.packageType = packageType;
      this.cpuFilters = cpuFilters;

      addDep(uberRDotJava.getBuildTarget());
      if (manifest instanceof BuildTargetSourcePath) {
        addDep(((BuildTargetSourcePath) manifest).getTarget());
      }
      for (BuildTarget nativeTarget : nativeTargetsWithAssets) {
        addDep(nativeTarget);
      }

      return this;
    }

    @Override
    protected AaptPackageResources newBuildable(BuildRuleParams params, BuildRuleResolver resolver) {
      return new AaptPackageResources(getBuildTarget(),
          manifest,
          uberRDotJava,
          packageType,
          cpuFilters);
    }
  }
}
