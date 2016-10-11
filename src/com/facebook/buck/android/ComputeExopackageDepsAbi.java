/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.android.AndroidBinary.ExopackageMode;
import com.facebook.buck.android.ComputeExopackageDepsAbi.BuildOutput;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A build rule that hashes all of the files that go into an exopackage APK.
 * This is used by AndroidBinaryRule to compute the ABI hash of its deps.
 */
public class ComputeExopackageDepsAbi extends AbstractBuildRule
    implements InitializableFromDisk<BuildOutput> {

  private static final Logger LOG = Logger.get(ComputeExopackageDepsAbi.class);

  private static final String METADATA_KEY = "EXOPACKAGE_ABI_OF_DEPS";

  private final EnumSet<ExopackageMode> exopackageModes;
  private final AndroidPackageableCollection packageableCollection;
  private final AaptPackageResources aaptPackageResources;
  private final Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibraries;
  private final Optional<PackageStringAssets> packageStringAssets;
  private final Optional<PreDexMerge> preDexMerge;
  private final Keystore keystore;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  public ComputeExopackageDepsAbi(
      BuildRuleParams params,
      SourcePathResolver resolver,
      EnumSet<ExopackageMode> exopackageModes,
      AndroidPackageableCollection packageableCollection,
      AaptPackageResources aaptPackageResources,
      Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibraries,
      Optional<PackageStringAssets> packageStringAssets,
      Optional<PreDexMerge> preDexMerge,
      Keystore keystore) {
    super(params, resolver);
    this.exopackageModes = exopackageModes;
    this.packageableCollection = packageableCollection;
    this.aaptPackageResources = aaptPackageResources;
    this.copyNativeLibraries = copyNativeLibraries;
    this.packageStringAssets = packageStringAssets;
    this.preDexMerge = preDexMerge;
    this.keystore = keystore;
    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, final BuildableContext buildableContext) {
    return ImmutableList.of(
        new AbstractExecutionStep("compute_android_binary_deps_abi") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) {
            try {

              // For exopackages, the only significant thing android_binary does is apkbuilder,
              // so we need to include all of the apkbuilder inputs in the ABI key.
              final Hasher hasher = Hashing.sha1().newHasher();
              // The first input to apkbuilder is the ap_ produced by aapt package.
              // Get its hash from the buildable that created it.
              String resourceApkHash = aaptPackageResources.getResourcePackageHash().toString();
              LOG.verbose("resource apk = %s", resourceApkHash);
              hasher.putUnencodedChars(resourceApkHash);
              // Next is the primary dex.  Same plan.
              String primaryDexHash = Preconditions.checkNotNull(
                  preDexMerge.get().getPrimaryDexHash())
                  .toString();
              LOG.verbose("primary dex = %s", primaryDexHash);
              hasher.putUnencodedChars(primaryDexHash);
              // Non-english strings packaged as assets.
              if (packageStringAssets.isPresent()) {
                String stringAssetsHash =
                    packageStringAssets.get().getStringAssetsZipHash().toString();
                LOG.verbose("string assets = %s", stringAssetsHash);
                hasher.putUnencodedChars(stringAssetsHash);
              }

              // We currently don't use any resource directories, so nothing to add there.

              // Collect files whose sha1 hashes need to be added to our ABI key.
              // This maps from the file to the role it plays, so changing (for example)
              // a native library to an asset will change the ABI key.
              // We assume that these are all order-insensitive to avoid having our ABI key
              // affected by filesystem iteration order.

              // If exopackage is disabled for secondary dexes, we need to hash the secondary dex
              // files that end up in the APK. PreDexMerge already hashes those files, so we can
              // just hash the summary of those hashes.
              if (!ExopackageMode.enabledForSecondaryDexes(exopackageModes) &&
                  preDexMerge.isPresent()) {
                addToHash(hasher, "secondary_dexes", preDexMerge.get().getMetadataTxtPath());
              }

              // If exopackage is disabled for native libraries, we add them in apkbuilder, so we
              // need to include their hashes. AndroidTransitiveDependencies doesn't provide
              // BuildRules, only paths. We could augment it, but our current native libraries are
              // small enough that we can just hash them all without too much of a perf hit.
              if (!ExopackageMode.enabledForNativeLibraries(exopackageModes) &&
                  copyNativeLibraries.isPresent()) {
                for (Map.Entry<APKModule, CopyNativeLibraries> entry :
                    copyNativeLibraries.get().entrySet()) {
                  addToHash(
                      hasher,
                      "native_libs_" + entry.getKey().getName(),
                      entry.getValue().getPathToMetadataTxt());
                }
              }

              // In native exopackage mode, we include a bundle of fake
              // libraries that makes multi-arch Android always put our application
              // in 32-bit mode.
              if (ExopackageMode.enabledForNativeLibraries(exopackageModes)) {
                String fakeNativeLibraryBundle =
                    System.getProperty("buck.native_exopackage_fake_path");

                Preconditions.checkNotNull(
                    fakeNativeLibraryBundle,
                    "fake native bundle not specified in properties.");

                Path fakePath = Paths.get(fakeNativeLibraryBundle);
                Preconditions.checkState(
                    fakePath.isAbsolute(),
                    "Expected fake path to be absolute: %s",
                    fakePath);

                addToHash(hasher, "fake_native_libs", fakePath, fakePath.getFileName());
              }

              // Same deal for native libs as assets.
              final ImmutableSortedMap.Builder<Path, Path> allNativeFiles =
                  ImmutableSortedMap.naturalOrder();
              for (SourcePath libDir :
                  packageableCollection.getNativeLibAssetsDirectories().values()) {
                // A SourcePath may not come from the same ProjectFilesystem as the step. Yay. The
                // `getFilesUnderPath` method returns files relative to the ProjectFilesystem's root
                // and so they may not exist, but we could go and do some path manipulation to
                // figure out where they are. Since we'll have to do the work anyway, let's just
                // handle things ourselves.
                final Path root = getResolver().getAbsolutePath(libDir);

                Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<Path>() {
                      @Override
                      public FileVisitResult visitFile(
                          Path file, BasicFileAttributes attrs) throws IOException {
                        allNativeFiles.put(file, root.relativize(file));
                        return FileVisitResult.CONTINUE;
                      }
                    });
              }
              for (Map.Entry<Path, Path> entry : allNativeFiles.build().entrySet()) {
                addToHash(hasher, "native_lib_as_asset", entry.getKey(), entry.getValue());
              }

              // Resources get copied from third-party JARs, so hash them.
              for (SourcePath jar :
                  ImmutableSortedSet.copyOf(packageableCollection.getPathsToThirdPartyJars())) {
                addToHash(hasher, "third-party jar", jar);
              }

              // The last input is the keystore.
              addToHash(hasher, "keystore", keystore.getPathToStore());
              addToHash(hasher, "keystore properties", keystore.getPathToPropertiesFile());

              String abiHash = hasher.hash().toString();
              LOG.verbose("ABI hash = %s", abiHash);
              buildableContext.addMetadata(METADATA_KEY, abiHash);
              return StepExecutionResult.SUCCESS;
            } catch (IOException e) {
              context.logError(e, "Error computing ABI hash.");
              return StepExecutionResult.ERROR;
            }
          }
        });
  }

  private void addToHash(Hasher hasher, String role, SourcePath path) throws IOException {
    addToHash(
        hasher,
        role,
        getResolver().getAbsolutePath(path),
        getResolver().getRelativePath(path));
  }

  private void addToHash(Hasher hasher, String role, Path path) throws IOException {
    Preconditions.checkState(!path.isAbsolute(), "Input path must not be absolute: %s", path);
    addToHash(hasher, role, getProjectFilesystem().resolve(path), path);
  }

  private void addToHash(Hasher hasher, String role, Path absolutePath, Path relativePath)
      throws IOException {
    // No need to check relative path. That's already been done for us.
    Preconditions.checkState(
        absolutePath.isAbsolute(),
        "Expected absolute path to be absolute: %s",
        absolutePath);

    hasher.putUnencodedChars(relativePath.toString());
    hasher.putByte((byte) 0);
    Sha1HashCode fileSha1 = getProjectFilesystem().computeSha1(absolutePath);
    fileSha1.update(hasher);
    hasher.putByte((byte) 0);
    hasher.putUnencodedChars(role);
    hasher.putByte((byte) 0);
    LOG.verbose("file %s(%s) = %s", relativePath, role, fileSha1);
  }

  @Nullable
  @Override
  public Path getPathToOutput() {
    return null;
  }

  public Sha1HashCode getAndroidBinaryAbiHash() {
    return buildOutputInitializer.getBuildOutput().abiHash;
  }

  static class BuildOutput {
    private final Sha1HashCode abiHash;

    BuildOutput(Sha1HashCode abiHash) {
      this.abiHash = abiHash;
    }
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    return new BuildOutput(onDiskBuildInfo.getHash(METADATA_KEY).get());
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }
}
