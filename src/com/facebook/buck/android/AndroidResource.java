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
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RecordFileSha1Step;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * An object that represents the resources of an android library.
 * <p>
 * Suppose this were a rule defined in <code>src/com/facebook/feed/BUILD</code>:
 * <pre>
 * android_resources(
 *   name = 'res',
 *   res = 'res',
 *   assets = 'buck-assets',
 *   deps = [
 *     '//first-party/orca/lib-ui:lib-ui',
 *   ],
 * )
 * </pre>
 */
public class AndroidResource extends AbstractBuildable
    implements AbiRule, HasAndroidResourceDeps, InitializableFromDisk<AndroidResource.BuildOutput> {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, LIBRARY);

  @VisibleForTesting
  static final String METADATA_KEY_FOR_ABI = "ANDROID_RESOURCE_ABI_KEY";

  /** {@link Function} that invokes {@link #getRes()} on an {@link AndroidResource}. */
  private static final Function<HasAndroidResourceDeps, Path> GET_RES_FOR_RULE =
      new Function<HasAndroidResourceDeps, Path>() {
    @Override
    @Nullable
    public Path apply(HasAndroidResourceDeps rule) {
      return rule.getRes();
    }
  };

  private final BuildTarget buildTarget;

  @Nullable
  private final Path res;

  private final ImmutableSortedSet<Path> resSrcs;

  @Nullable
  private final String rDotJavaPackage;

  @Nullable
  private final Path assets;

  private final ImmutableSortedSet<Path> assetsSrcs;

  @Nullable
  private final Path pathToTextSymbolsDir;

  @Nullable
  private final Path pathToTextSymbolsFile;

  @Nullable
  private final Path manifestFile;

  private final boolean hasWhitelistedStrings;

  private final Supplier<ImmutableList<HasAndroidResourceDeps>> transitiveAndroidResourceDeps;

  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  protected AndroidResource(
      BuildTarget buildTarget,
      final ImmutableSortedSet<BuildRule> deps,
      @Nullable final Path res,
      ImmutableSortedSet<Path> resSrcs,
      @Nullable String rDotJavaPackage,
      @Nullable Path assets,
      ImmutableSortedSet<Path> assetsSrcs,
      @Nullable Path manifestFile,
      boolean hasWhitelistedStrings) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.res = res;
    this.resSrcs = Preconditions.checkNotNull(resSrcs);
    this.rDotJavaPackage = rDotJavaPackage;
    this.assets = assets;
    this.assetsSrcs = Preconditions.checkNotNull(assetsSrcs);
    this.manifestFile = manifestFile;
    this.hasWhitelistedStrings = hasWhitelistedStrings;

    if (res == null) {
      pathToTextSymbolsDir = null;
      pathToTextSymbolsFile = null;
    } else {
      pathToTextSymbolsDir = BuildTargets.getGenPath(buildTarget, "__%s_text_symbols__");
      pathToTextSymbolsFile = pathToTextSymbolsDir.resolve("R.txt");
    }

    this.transitiveAndroidResourceDeps = Suppliers.memoize(
        new Supplier<ImmutableList<HasAndroidResourceDeps>>() {
          @Override
          public ImmutableList<HasAndroidResourceDeps> get() {
            ImmutableList.Builder<HasAndroidResourceDeps> resDeps = ImmutableList.builder();
            if (res != null) {
              resDeps.add(AndroidResource.this);
            }
            resDeps.addAll(UberRDotJavaUtil.getAndroidResourceDeps(deps));
            return resDeps.build();
          }
        });

    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    ImmutableSortedSet.Builder<Path> inputs = ImmutableSortedSet.naturalOrder();

    // This should include the res/ and assets/ folders.
    inputs.addAll(resSrcs);
    inputs.addAll(assetsSrcs);

    // manifest file is optional.
    if (manifestFile != null) {
      inputs.add(manifestFile);
    }

    return inputs.build();
  }

  @Override
  @Nullable
  public Path getRes() {
    return res;
  }

  @Override
  public boolean hasWhitelistedStrings() {
    return hasWhitelistedStrings;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  @Nullable
  public Path getAssets() {
    return assets;
  }

  @Nullable
  public Path getManifestFile() {
    return manifestFile;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, final BuildableContext buildableContext) {
    // If there is no res directory, then there is no R.java to generate.
    // TODO(mbolin): Change android_resources() so that 'res' is required.
    if (getRes() == null) {
      // Normally, the ABI key of a resource rule is a hash of the R.txt file that it creates.
      // That R.txt essentially combines all the R.txt files generated by transitive dependencies of
      // this rule. So, in this case (when we don't have the R.txt), the right ABI key is the
      // ABI key for deps.
      buildableContext.addMetadata(METADATA_KEY_FOR_ABI, getAbiKeyForDeps().getHash());
      return ImmutableList.of();
    }

    MakeCleanDirectoryStep mkdir = new MakeCleanDirectoryStep(pathToTextSymbolsDir);

    // Searching through the deps, find any additional res directories to pass to aapt.
    Set<Path> resDirectories = ImmutableSet.copyOf(
        Iterables.transform(transitiveAndroidResourceDeps.get(), GET_RES_FOR_RULE));

    GenRDotJavaStep genRDotJava = new GenRDotJavaStep(
        resDirectories,
        pathToTextSymbolsDir,
        rDotJavaPackage,
        /* isTempRDotJava */ true,
        /* extraLibraryPackages */ ImmutableSet.<String>of());

    buildableContext.recordArtifact(pathToTextSymbolsFile);

    RecordFileSha1Step recordRDotTxtSha1 = new RecordFileSha1Step(
        pathToTextSymbolsFile,
        METADATA_KEY_FOR_ABI,
        buildableContext);

    return ImmutableList.of(mkdir, genRDotJava, recordRDotTxtSha1);
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("rDotJavaPackage", rDotJavaPackage)
        .set("hasWhitelistedStrings", hasWhitelistedStrings);
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    return Optional.fromNullable(pathToTextSymbolsFile).orNull();
  }

  @Override
  @Nullable
  public Path getPathToTextSymbolsFile() {
    return pathToTextSymbolsFile;
  }

  @Override
  public Sha1HashCode getTextSymbolsAbiKey() {
    return buildOutputInitializer.getBuildOutput().textSymbolsAbiKey;
  }

  @Override
  public String getRDotJavaPackage() {
    if (rDotJavaPackage == null) {
      throw new RuntimeException("No package for " + buildTarget.getFullyQualifiedName());
    }
    return rDotJavaPackage;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    // We hash the transitive dependencies and not just the first order deps because we pass these
    // to aapt to generate R.java/R.txt.
    // Transitive dependencies includes this rule itself; filter it out.
    return HasAndroidResourceDeps.ABI_HASHER.apply(Iterables.filter(
        transitiveAndroidResourceDeps.get(),
        Predicates.not(
            Predicates.equalTo((HasAndroidResourceDeps) AndroidResource.this))));
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    Sha1HashCode sha1HashCode = onDiskBuildInfo.getHash(METADATA_KEY_FOR_ABI).get();
    return new BuildOutput(sha1HashCode);
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  public static class BuildOutput {
    private final Sha1HashCode textSymbolsAbiKey;

    public BuildOutput(Sha1HashCode textSymbolsAbiKey) {
      this.textSymbolsAbiKey = Preconditions.checkNotNull(textSymbolsAbiKey);
    }
  }
}
