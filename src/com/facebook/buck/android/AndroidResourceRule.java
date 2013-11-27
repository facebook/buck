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
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
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
public class AndroidResourceRule extends DoNotUseAbstractBuildable implements HasAndroidResourceDeps {

  private final static BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, LIBRARY);

  /** {@link Function} that invokes {@link #getRes()} on an {@link AndroidResourceRule}. */
  private static final Function<HasAndroidResourceDeps, String> GET_RES_FOR_RULE =
      new Function<HasAndroidResourceDeps, String>() {
    @Override
    @Nullable
    public String apply(HasAndroidResourceDeps rule) {
      return rule.getRes();
    }
  };

  @Nullable
  private final String res;

  private final ImmutableSortedSet<String> resSrcs;

  @Nullable
  private final String rDotJavaPackage;

  @Nullable
  private final String assets;

  private final ImmutableSortedSet<String> assetsSrcs;

  @Nullable
  private final String pathToTextSymbolsDir;

  @Nullable
  private final String pathToTextSymbolsFile;

  @Nullable
  private final String manifestFile;

  private final boolean hasWhitelistedStrings;

  protected AndroidResourceRule(BuildRuleParams buildRuleParams,
      @Nullable String res,
      ImmutableSortedSet<String> resSrcs,
      @Nullable String rDotJavaPackage,
      @Nullable String assets,
      ImmutableSortedSet<String> assetsSrcs,
      @Nullable String manifestFile,
      boolean hasWhitelistedStrings) {
    super(buildRuleParams);
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
      BuildTarget buildTarget = buildRuleParams.getBuildTarget();
      pathToTextSymbolsDir = String.format("%s/%s__%s_text_symbols__",
          BuckConstant.GEN_DIR,
          buildTarget.getBasePathWithSlash(),
          buildTarget.getShortName());
      pathToTextSymbolsFile = pathToTextSymbolsDir + "/R.txt";
    }
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    ImmutableList.Builder<String> inputsToConsiderForCachingPurposes = ImmutableList.builder();

    // This should include the res/ and assets/ folders.
    inputsToConsiderForCachingPurposes.addAll(resSrcs);
    inputsToConsiderForCachingPurposes.addAll(assetsSrcs);

    // manifest file is optional.
    if (manifestFile != null) {
      inputsToConsiderForCachingPurposes.add(manifestFile);
    }

    return inputsToConsiderForCachingPurposes.build();
  }

  @Override
  @Nullable
  public String getRes() {
    return res;
  }

  @Override
  public boolean hasWhitelistedStrings() {
    return hasWhitelistedStrings;
  }

  @Nullable
  public String getAssets() {
    return assets;
  }

  @Nullable
  public String getManifestFile() {
    return manifestFile;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    // If there is no res directory, then there is no R.java to generate.
    // TODO(mbolin): Change android_resources() so that 'res' is required.
    if (getRes() == null) {
      return ImmutableList.of();
    }

    MakeCleanDirectoryStep mkdir = new MakeCleanDirectoryStep(pathToTextSymbolsDir);

    // Searching through the deps, find any additional res directories to pass to aapt.
    ImmutableList<HasAndroidResourceDeps> androidResourceDeps = UberRDotJavaUtil.getAndroidResourceDeps(
        this);
    Set<String> resDirectories = ImmutableSet.copyOf(
        Iterables.transform(androidResourceDeps, GET_RES_FOR_RULE));

    GenRDotJavaStep genRDotJava = new GenRDotJavaStep(
        resDirectories,
        pathToTextSymbolsDir,
        rDotJavaPackage,
        /* isTempRDotJava */ true,
        /* extraLibraryPackages */ ImmutableSet.<String>of());
    return ImmutableList.of(mkdir, genRDotJava);
  }

  @Override
  @Nullable
  public String getPathToOutputFile() {
    if (pathToTextSymbolsFile != null) {
      return pathToTextSymbolsFile;
    } else {
      return null;
    }
  }

  @Override
  @Nullable
  public String getPathToTextSymbolsFile() {
    return pathToTextSymbolsFile;
  }

  @Override
  public String getRDotJavaPackage() {
    if (rDotJavaPackage == null) {
      throw new RuntimeException("No package for " + getFullyQualifiedName());
    }
    return rDotJavaPackage;
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.ANDROID_RESOURCE;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    // TODO(#2493457): This rule uses the aapt binary (part of the Android SDK), so the RuleKey
    // should incorporate which version of aapt is used.
    return super.appendToRuleKey(builder)
        .set("res", resSrcs)
        .set("rDotJavaPackage", rDotJavaPackage)
        .set("assets", assetsSrcs)
        .set("manifestFile", manifestFile)
        .set("hasWhitelistedStrings", hasWhitelistedStrings);
  }

  public static Builder newAndroidResourceRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<AndroidResourceRule> {
    @Nullable
    private String res = null;

    private ImmutableSortedSet.Builder<String> resSrcs = ImmutableSortedSet.naturalOrder();

    @Nullable
    private String rDotJavaPackage = null;

    @Nullable
    private String assetsDirectory = null;

    private ImmutableSortedSet.Builder<String> assetsSrcs = ImmutableSortedSet.naturalOrder();

    @Nullable
    private String manifestFile = null;

    private boolean hasWhitelistedStrings = false;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public AndroidResourceRule build(BuildRuleResolver ruleResolver) {
      if ((res == null && rDotJavaPackage != null)
          || (res != null && rDotJavaPackage == null)) {
        throw new HumanReadableException("Both res and package must be set together in %s.",
            getBuildTarget().getFullyQualifiedName());
      }

      return new AndroidResourceRule(createBuildRuleParams(ruleResolver),
          res,
          resSrcs.build(),
          rDotJavaPackage,
          assetsDirectory,
          assetsSrcs.build(),
          manifestFile,
          hasWhitelistedStrings);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(BuildTarget dep) {
      super.addDep(dep);
      return this;
    }

    @Override
    public Builder addVisibilityPattern(BuildTargetPattern visibilityPattern) {
      visibilityPatterns.add(visibilityPattern);
      return this;
    }

    public Builder setRes(String res) {
      this.res = res;
      return this;
    }

    public Builder addResSrc(String resSrc) {
      this.resSrcs.add(resSrc);
      return this;
    }

    public Builder setRDotJavaPackage(String rDotJavaPackage) {
      this.rDotJavaPackage = rDotJavaPackage;
      return this;
    }

    public Builder setAssetsDirectory(String assets) {
      this.assetsDirectory = assets;
      return this;
    }

    public Builder addAssetsSrc(String assetsSrc) {
      this.assetsSrcs.add(assetsSrc);
      return this;
    }

    public Builder setManifestFile(String manifestFile) {
      this.manifestFile = manifestFile;
      return this;
    }

    public Builder setHasWhitelistedStrings(boolean hasWhitelistedStrings) {
      this.hasWhitelistedStrings = hasWhitelistedStrings;
      return this;
    }
  }
}
