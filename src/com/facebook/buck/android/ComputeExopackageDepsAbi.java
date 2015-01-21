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
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A buildable that hashes all of the files that go into an exopackage APK.
 * This is used by AndroidBinaryRule to compute the ABI hash of its deps.
 */
public class ComputeExopackageDepsAbi extends AbstractBuildRule
    implements InitializableFromDisk<BuildOutput> {

  private static final Logger LOG = Logger.get(ComputeExopackageDepsAbi.class);

  private static final String METADATA_KEY = "EXOPACKAGE_ABI_OF_DEPS";

  private final EnumSet<ExopackageMode> exopackageModes;
  private final AndroidPackageableCollection packageableCollection;
  private final AaptPackageResources aaptPackageResources;
  private final Optional<CopyNativeLibraries> copyNativeLibraries;
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
      Optional<CopyNativeLibraries> copyNativeLibraries,
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
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, final BuildableContext buildableContext) {
    return ImmutableList.<Step>of(
        new AbstractExecutionStep("compute_android_binary_deps_abi") {
          @Override
          public int execute(ExecutionContext context) {
            try {
              ProjectFilesystem filesystem = context.getProjectFilesystem();

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
              ImmutableSortedMap.Builder<Path, String> filesToHash =
                  ImmutableSortedMap.naturalOrder();

              // If exopackage is disabled for secondary dexes, we need to hash the secondary dex
              // files that end up in the APK. PreDexMerge already hashes those files, so we can
              // just hash the summary of those hashes.
              if (!ExopackageMode.enabledForSecondaryDexes(exopackageModes) &&
                  preDexMerge.isPresent()) {
                filesToHash.put(preDexMerge.get().getMetadataTxtPath(), "secondary_dexes");
              }

              // If exopackage is disabled for native libraries, we add them in apkbuilder, so we
              // need to include their hashes. AndroidTransitiveDependencies doesn't provide
              // BuildRules, only paths. We could augment it, but our current native libraries are
              // small enough that we can just hash them all without too much of a perf hit.
              if (!ExopackageMode.enabledForNativeLibraries(exopackageModes) &&
                  copyNativeLibraries.isPresent()) {
                filesToHash.put(copyNativeLibraries.get().getPathToMetadataTxt(), "native_libs");
              }

              // Same deal for native libs as assets.
              for (final Path libDir : packageableCollection.getNativeLibAssetsDirectories()) {
                for (Path nativeFile : filesystem.getFilesUnderPath(libDir)) {
                  filesToHash.put(nativeFile, "native_lib_as_asset");
                }
              }

              // Resources get copied from third-party JARs, so hash them.
              for (Path jar : packageableCollection.getPathsToThirdPartyJars()) {
                filesToHash.put(jar, "third-party jar");
              }

              // The last input is the keystore.
              filesToHash.put(keystore.getPathToStore(), "keystore");
              filesToHash.put(keystore.getPathToPropertiesFile(), "keystore properties");

              for (Map.Entry<Path, String> entry : filesToHash.build().entrySet()) {
                Path path = entry.getKey();
                hasher.putUnencodedChars(path.toString());
                hasher.putByte((byte) 0);
                String fileSha1 = filesystem.computeSha1(path);
                hasher.putUnencodedChars(fileSha1);
                hasher.putByte((byte) 0);
                hasher.putUnencodedChars(entry.getValue());
                hasher.putByte((byte) 0);
                LOG.verbose("file %s(%s) = %s", path, entry.getValue(), fileSha1);
              }

              String abiHash = hasher.hash().toString();
              LOG.verbose("ABI hash = %s", abiHash);
              buildableContext.addMetadata(METADATA_KEY, abiHash);
              return 0;
            } catch (IOException e) {
              context.logError(e, "Error computing ABI hash.");
              return 1;
            }
          }
        });
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
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
