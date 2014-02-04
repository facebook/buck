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
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
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
  private final Optional<PreDexMerge> preDexMerge;
  private final Keystore keystore;

  public ComputeExopackageDepsAbi(
      AndroidResourceDepsFinder androidResourceDepsFinder,
      UberRDotJava uberRDotJava,
      AaptPackageResources aaptPackageResources,
      Optional<PreDexMerge> preDexMerge,
      Keystore keystore) {
    this.androidResourceDepsFinder = Preconditions.checkNotNull(androidResourceDepsFinder);
    this.uberRDotJava = Preconditions.checkNotNull(uberRDotJava);
    this.aaptPackageResources = Preconditions.checkNotNull(aaptPackageResources);
    this.preDexMerge = Preconditions.checkNotNull(preDexMerge);
    this.keystore = Preconditions.checkNotNull(keystore);
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return ImmutableList.of();
  }

  @Override
  public List<Step> getBuildSteps(
      BuildContext context, final BuildableContext buildableContext) throws IOException {
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

              // We currently don't use any resource directories, so nothing to add there.

              // Collect files whose sha1 hashes need to be added to our ABI key.
              // This maps from the file to the role it plays, so changing (for example)
              // a native library to an asset will change the ABI key.
              // We assume that these are all order-insensitive to avoid having our ABI key
              // affected by filesystem iteration order.
              final ImmutableSortedMap.Builder<Path, String> filesToHash =
                  ImmutableSortedMap.naturalOrder();

              // We add native libraries in apkbuilder, so we need to include their hashes.
              // AndroidTransitiveDependencies doesn't provide BuildRules, only paths.
              // We could augment it, but our current native libraries are small enough that
              // we can just hash them all without too much of a perf hit.
              for (final Path libDir : transitiveDependencies.nativeLibsDirectories) {
                new DirectoryTraversal(filesystem.resolve(libDir).toFile()) {
                  @Override
                  public void visit(File file, String relativePath) throws IOException {
                    filesToHash.put(libDir.resolve(relativePath), "native lib");
                  }
                }.traverse();
              }

              // Resources get copied from third-party JARs, so hash them.
              for (String jar : dexTransitiveDependencies.pathsToThirdPartyJars) {
                filesToHash.put(Paths.get(jar), "third-party jar");
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
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    return builder;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  public Sha1HashCode getAndroidBinaryAbiHash() {
    return getBuildOutput().abiHash;
  }

  static class BuildOutput {
    private final Sha1HashCode abiHash;

    BuildOutput(Sha1HashCode abiHash) {
      this.abiHash = Preconditions.checkNotNull(abiHash);
    }
  }

  @Nullable
  private BuildOutput buildOutput;

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    return new BuildOutput(onDiskBuildInfo.getHash(METADATA_KEY).get());
  }

  @Override
  public void setBuildOutput(BuildOutput buildOutput) {
    Preconditions.checkState(this.buildOutput == null,
        "buildOutput should not already be set for %s.",
        this);
    this.buildOutput = buildOutput;
  }

  @Override
  public BuildOutput getBuildOutput() {
    Preconditions.checkState(buildOutput != null, "buildOutput must already be set for %s.", this);
    return buildOutput;
  }

  public static Builder newBuildableBuilder(
      AbstractBuildRuleBuilderParams params,
      BuildTarget buildTarget,
      Collection<BuildRule> deps,
      AndroidResourceDepsFinder androidResourceDepsFinder,
      UberRDotJava uberRDotJava,
      AaptPackageResources aaptPackageResources,
      Optional<PreDexMerge> preDexMerge,
      Keystore keystore) {
    Builder builder = new Builder(params);
    builder.setBuildTarget(buildTarget);
    builder.androidResourceDepsFinder = androidResourceDepsFinder;
    builder.uberRDotJava = uberRDotJava;
    builder.aaptPackageResources = aaptPackageResources;
    builder.preDexMerge = preDexMerge;
    builder.keystore = keystore;
    for (BuildRule dep : deps) {
      builder.addDep(dep.getBuildTarget());
    }
    return builder;
  }

  static class Builder extends AbstractBuildable.Builder {
    @Nullable private AndroidResourceDepsFinder androidResourceDepsFinder;
    @Nullable private UberRDotJava uberRDotJava;
    @Nullable private AaptPackageResources aaptPackageResources;
    @Nullable private Optional<PreDexMerge> preDexMerge;
    @Nullable private Keystore keystore;

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    protected BuildRuleType getType() {
      return BuildRuleType.AAPT_PACKAGE;
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    protected ComputeExopackageDepsAbi newBuildable(BuildRuleParams params,
        BuildRuleResolver resolver) {
      return new ComputeExopackageDepsAbi(
          androidResourceDepsFinder,
          uberRDotJava,
          aaptPackageResources,
          preDexMerge,
          keystore);
    }
  }
}
