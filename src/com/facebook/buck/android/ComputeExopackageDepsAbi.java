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

import com.facebook.buck.android.ComputeExopackageDepsAbi.BuildOutput;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A buildable that hashes all of the files that go into an exopackage APK.
 * This is used by AndroidBinaryRule to compute the ABI hash of its deps.
 */
public class ComputeExopackageDepsAbi
    extends AbstractBuildable
    implements InitializableFromDisk<BuildOutput> {
  private static final String METADATA_KEY = "EXOPACKAGE_ABI_OF_DEPS";

  private final AndroidResourceDepsFinder androidResourceDepsFinder;
  private final UberRDotJava uberRDotJava;
  private final AaptPackageResources aaptPackageResources;
  private final Optional<PackageStringAssets> packageStringAssets;
  private final Optional<PreDexMerge> preDexMerge;
  private final Keystore keystore;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  public ComputeExopackageDepsAbi(
      BuildTarget buildTarget,
      AndroidResourceDepsFinder androidResourceDepsFinder,
      UberRDotJava uberRDotJava,
      AaptPackageResources aaptPackageResources,
      Optional<PackageStringAssets> packageStringAssets,
      Optional<PreDexMerge> preDexMerge,
      Keystore keystore) {
    super(buildTarget);
    this.androidResourceDepsFinder = Preconditions.checkNotNull(androidResourceDepsFinder);
    this.uberRDotJava = Preconditions.checkNotNull(uberRDotJava);
    this.aaptPackageResources = Preconditions.checkNotNull(aaptPackageResources);
    this.packageStringAssets = Preconditions.checkNotNull(packageStringAssets);
    this.preDexMerge = Preconditions.checkNotNull(preDexMerge);
    this.keystore = Preconditions.checkNotNull(keystore);
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
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

              AndroidTransitiveDependencies transitiveDependencies =
                  androidResourceDepsFinder.getAndroidTransitiveDependencies();
              AndroidDexTransitiveDependencies dexTransitiveDependencies =
                  androidResourceDepsFinder.getAndroidDexTransitiveDependencies(uberRDotJava);

              // For exopackages, the only significant thing android_binary does is apkbuilder,
              // so we need to include all of the apkbuilder inputs in the ABI key.
              final Hasher hasher = Hashing.sha1().newHasher();
              // The first input to apkbuilder is the ap_ produced by aapt package.
              // Get its hash from the buildable that created it.
              hasher.putUnencodedChars(aaptPackageResources.getResourcePackageHash().toString());
              // Next is the primary dex.  Same plan.
              hasher.putUnencodedChars(preDexMerge.get().getPrimaryDexHash().toString());
              // Non-english strings packaged as assets.
              if (packageStringAssets.isPresent()) {
                hasher.putUnencodedChars(
                    packageStringAssets.get().getStringAssetsZipHash().toString());
              }

              // We currently don't use any resource directories, so nothing to add there.

              // Collect files whose sha1 hashes need to be added to our ABI key.
              // This maps from the file to the role it plays, so changing (for example)
              // a native library to an asset will change the ABI key.
              // We assume that these are all order-insensitive to avoid having our ABI key
              // affected by filesystem iteration order.
              ImmutableSortedMap.Builder<Path, String> filesToHash =
                  ImmutableSortedMap.naturalOrder();

              // We add native libraries in apkbuilder, so we need to include their hashes.
              // AndroidTransitiveDependencies doesn't provide BuildRules, only paths.
              // We could augment it, but our current native libraries are small enough that
              // we can just hash them all without too much of a perf hit.
              for (final Path libDir : transitiveDependencies.nativeLibsDirectories) {
                for (Path nativeFile : filesystem.getFilesUnderPath(libDir)) {
                  filesToHash.put(nativeFile, "native_lib");
                }
              }

              // Same deal for native libs as assets.
              for (final Path libDir : transitiveDependencies.nativeLibAssetsDirectories) {
                for (Path nativeFile : filesystem.getFilesUnderPath(libDir)) {
                  filesToHash.put(nativeFile, "native_lib_as_asset");
                }
              }

              // Resources get copied from third-party JARs, so hash them.
              for (Path jar : dexTransitiveDependencies.pathsToThirdPartyJars) {
                filesToHash.put(jar, "third-party jar");
              }

              // The last input is the keystore.
              filesToHash.put(keystore.getPathToStore(), "keystore");
              filesToHash.put(keystore.getPathToPropertiesFile(), "keystore properties");

              for (Map.Entry<Path, String> entry : filesToHash.build().entrySet()) {
                Path path = entry.getKey();
                hasher.putUnencodedChars(path.toString());
                hasher.putByte((byte) 0);
                hasher.putUnencodedChars(filesystem.computeSha1(path));
                hasher.putByte((byte) 0);
                hasher.putUnencodedChars(entry.getValue());
                hasher.putByte((byte) 0);
              }

              buildableContext.addMetadata(METADATA_KEY, hasher.hash().toString());
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
      this.abiHash = Preconditions.checkNotNull(abiHash);
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
