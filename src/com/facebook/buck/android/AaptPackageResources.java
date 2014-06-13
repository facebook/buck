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

import com.facebook.buck.android.AaptPackageResources.BuildOutput;
import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.android.AndroidBinary.TargetCpuType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RecordFileSha1Step;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Packages the resources using {@code aapt}.
 */
public class AaptPackageResources extends AbstractBuildable
    implements InitializableFromDisk<BuildOutput> {

  public static final String RESOURCE_PACKAGE_HASH_KEY = "resource_package_hash";

  private final SourcePath manifest;
  private final FilteredResourcesProvider filteredResourcesProvider;
  private final ImmutableSet<Path> assetsDirectories;
  private final PackageType packageType;
  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  AaptPackageResources(
      BuildTarget buildTarget,
      SourcePath manifest,
      FilteredResourcesProvider filteredResourcesProvider,
      ImmutableSet<Path> assetsDirectories,
      PackageType packageType,
      ImmutableSet<TargetCpuType> cpuFilters) {
    super(buildTarget);
    this.manifest = Preconditions.checkNotNull(manifest);
    this.filteredResourcesProvider = Preconditions.checkNotNull(filteredResourcesProvider);
    this.assetsDirectories = Preconditions.checkNotNull(assetsDirectories);
    this.packageType = Preconditions.checkNotNull(packageType);
    this.cpuFilters = Preconditions.checkNotNull(cpuFilters);
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(Collections.singleton(manifest));
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("packageType", packageType.toString())
        .set("cpuFilters", ImmutableSortedSet.copyOf(cpuFilters).toString());
  }

  @Override
  public Path getPathToOutputFile() {
    return getResourceApkPath();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Symlink the manifest to a path named AndroidManifest.xml. Do this before running any other
    // commands to ensure that it is available at the desired path.
    steps.add(new MkdirAndSymlinkFileStep(manifest.resolve(), getAndroidManifestXml()));

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
              assetsDirectories,
              commands,
              context.getProjectFilesystem());
        } catch (IOException e) {
          context.logError(e, "Error creating all assets directory in %s.", target);
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
    if (assetsDirectories.isEmpty()) {
      assetsDirectory = Optional.absent();
    } else {
      assetsDirectory = Optional.of(getPathToAllAssetsDirectory());
    }

    steps.add(new MkdirStep(getResourceApkPath().getParent()));

    steps.add(new AaptStep(
        getAndroidManifestXml(),
        filteredResourcesProvider.getResDirectories(),
        assetsDirectory,
        getResourceApkPath(),
        packageType.isCrunchPngFiles()));

    buildableContext.recordArtifact(getAndroidManifestXml());
    buildableContext.recordArtifact(getResourceApkPath());

    steps.add(new RecordFileSha1Step(
        getResourceApkPath(),
        RESOURCE_PACKAGE_HASH_KEY,
        buildableContext));

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
    return BuildTargets.getBinPath(target, "__manifest_%s__/AndroidManifest.xml");
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
      ProjectFilesystem filesystem) throws IOException {
    if (assetsDirectories.isEmpty()) {
      return Optional.absent();
    }

    // Due to a limitation of aapt, only one assets directory can be specified, so if multiple are
    // specified in Buck, then all of the contents must be symlinked to a single directory.
    Path destination = getPathToAllAssetsDirectory();
    steps.add(new MakeCleanDirectoryStep(destination));
    final ImmutableMap.Builder<Path, Path> allAssets = ImmutableMap.builder();

    for (final Path assetsDirectory : assetsDirectories) {
      filesystem.walkRelativeFileTree(
          assetsDirectory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              allAssets.put(assetsDirectory.relativize(file), file);
              return FileVisitResult.CONTINUE;
            }
          });
    }

    for (Map.Entry<Path, Path> entry : allAssets.build().entrySet()) {
      steps.add(new MkdirAndSymlinkFileStep(
          entry.getValue(),
          destination.resolve(entry.getKey())));
    }

    return Optional.of(destination);
  }

  /**
   * @return Path to the unsigned APK generated by this {@link com.facebook.buck.rules.Buildable}.
   */
  public Path getResourceApkPath() {
    return BuildTargets.getGenPath(target, "%s.unsigned.ap_");
  }

  @VisibleForTesting
  Path getPathToAllAssetsDirectory() {
    return BuildTargets.getBinPath(target, "__assets_%s__");
  }

  public Sha1HashCode getResourcePackageHash() {
    return buildOutputInitializer.getBuildOutput().resourcePackageHash;
  }

  static class BuildOutput {
    private final Sha1HashCode resourcePackageHash;

    BuildOutput(Sha1HashCode resourcePackageHash) {
      this.resourcePackageHash = Preconditions.checkNotNull(resourcePackageHash);
    }
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    Optional<Sha1HashCode> resourcePackageHash = onDiskBuildInfo.getHash(RESOURCE_PACKAGE_HASH_KEY);
    Preconditions.checkState(
        resourcePackageHash.isPresent(),
        "Should not be initializing %s from disk if the resource hash is not written.",
        target);
    return new BuildOutput(resourcePackageHash.get());
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }
}
