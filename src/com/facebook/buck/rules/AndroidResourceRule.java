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

package com.facebook.buck.rules;

import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.shell.Command;
import com.facebook.buck.shell.GenRDotJavaCommand;
import com.facebook.buck.shell.MakeCleanDirectoryCommand;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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
public class AndroidResourceRule extends AbstractCachingBuildRule {

  /** {@link Function} that invokes {@link #getRes()} on an {@link AndroidResourceRule}. */
  private static final Function<AndroidResourceRule, String> GET_RES_FOR_RULE =
      new Function<AndroidResourceRule, String>() {
    @Override
    @Nullable
    public String apply(AndroidResourceRule rule) {
      return rule.getRes();
    }
  };

  private static final ImmutableSet<BuildRuleType> TRAVERSABLE_TYPES = ImmutableSet.of(
      BuildRuleType.ANDROID_BINARY,
      BuildRuleType.ANDROID_INSTRUMENTATION_APK,
      BuildRuleType.ANDROID_LIBRARY,
      BuildRuleType.ANDROID_RESOURCE,
      BuildRuleType.APK_GENRULE,
      BuildRuleType.JAVA_LIBRARY,
      BuildRuleType.JAVA_TEST,
      BuildRuleType.ROBOLECTRIC_TEST);

  private final DirectoryTraverser directoryTraverser;

  @Nullable
  private final String res;

  @Nullable
  private String rDotJavaPackage;

  @Nullable
  private final String assets;

  @Nullable
  private final String pathToTextSymbolsDir;

  @Nullable
  private final String pathToTextSymbolsFile;

  @Nullable
  private final String manifestFile;

  protected AndroidResourceRule(BuildRuleParams buildRuleParams,
      @Nullable String res,
      @Nullable String rDotJavaPackage,
      @Nullable String assets,
      @Nullable String manifestFile,
      DirectoryTraverser directoryTraverser) {
    super(buildRuleParams);
    this.directoryTraverser = Preconditions.checkNotNull(directoryTraverser);
    this.res = res;
    this.rDotJavaPackage = rDotJavaPackage;
    this.assets = assets;
    this.manifestFile = manifestFile;

    if (res == null) {
      pathToTextSymbolsDir = null;
      pathToTextSymbolsFile = null;
    } else {
      BuildTarget buildTarget = buildRuleParams.getBuildTarget();
      pathToTextSymbolsDir = String.format("%s/%s__%s_text_symbols__",
          BuckConstant.BIN_DIR,
          buildTarget.getBasePathWithSlash(),
          buildTarget.getShortName());
      pathToTextSymbolsFile = pathToTextSymbolsDir + "/R.txt";
    }
  }

  private void addResContents(ImmutableSortedSet.Builder<String> files) {
    addInputsToSortedSet(res, files, directoryTraverser);
  }

  private void addAssetsContents(ImmutableSortedSet.Builder<String> files) {
    addInputsToSortedSet(assets, files, directoryTraverser);
  }

  @Override
  protected Iterable<String> getInputsToCompareToOutput(BuildContext context) {
    ImmutableSortedSet.Builder<String> inputsToConsiderForCachingPurposes = ImmutableSortedSet
        .naturalOrder();

    // This should include the res/ and assets/ folders.
    addResContents(inputsToConsiderForCachingPurposes);
    addAssetsContents(inputsToConsiderForCachingPurposes);

    // manifest file is optional
    if (manifestFile != null) {
      inputsToConsiderForCachingPurposes.add(manifestFile);
    }

    return inputsToConsiderForCachingPurposes.build();
  }

  @Nullable
  public String getRes() {
    return res;
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
  protected List<Command> buildInternal(BuildContext context)
      throws IOException {
    // If there is no res directory, then there is no R.java to generate.
    // TODO(mbolin): Change android_resources() so that 'res' is required.
    if (getRes() == null) {
      return ImmutableList.of();
    }

    MakeCleanDirectoryCommand mkdir = new MakeCleanDirectoryCommand(pathToTextSymbolsDir);

    // Searching through the deps, find any additional res directories to pass to aapt.
    ImmutableList<AndroidResourceRule> androidResourceDeps = getAndroidResourceDeps(
        this, context.getDependencyGraph());
    Set<String> resDirectories = ImmutableSet.copyOf(
        Iterables.transform(androidResourceDeps, GET_RES_FOR_RULE));

    GenRDotJavaCommand genRDotJava = new GenRDotJavaCommand(
        resDirectories,
        pathToTextSymbolsDir,
        rDotJavaPackage,
        /* isTempRDotJava */ true,
        /* extraLibraryPackages */ ImmutableSet.<String>of());
    return ImmutableList.of(mkdir, genRDotJava);
  }

  /**
   * Finds the transitive set of {@code rule}'s {@link AndroidResourceRule} dependencies with
   * non-null {@code res} directories, which can also include {@code rule} itself.
   * This set will be returned as an {@link ImmutableList} with the rules topologically sorted as
   * determined by {@code graph}. Rules will be ordered from least dependent to most dependent.
   */
  static ImmutableList<AndroidResourceRule> getAndroidResourceDeps(
      BuildRule rule,
      DependencyGraph graph) {
    final Set<AndroidResourceRule> allAndroidResourceRules = findAllAndroidResourceDeps(rule);

    // Now that we have the transitive set of AndroidResourceRules, we need to return them in
    // topologically sorted order. This is critical because the order in which -S flags are passed
    // to aapt is significant and must be consistent.
    Predicate<BuildRule> inclusionPredicate = new Predicate<BuildRule>() {
      @Override
      public boolean apply(BuildRule rule) {
        return allAndroidResourceRules.contains(rule);
      }
    };
    ImmutableList<BuildRule> sortedAndroidResourceRules = TopologicalSort.sort(graph,
        inclusionPredicate);

    // TopologicalSort.sort() returns rules in leaves-first order, which is the opposite of what we
    // want, so we must reverse the list and cast BuildRules to AndroidResourceRules.
    return ImmutableList.copyOf(
        Iterables.transform(
            sortedAndroidResourceRules.reverse(),
            CAST_TO_ANDROID_RESOURCE_RULE)
        );
  }

  private static Function<BuildRule, AndroidResourceRule> CAST_TO_ANDROID_RESOURCE_RULE =
      new Function<BuildRule, AndroidResourceRule>() {
        @Override
        public AndroidResourceRule apply(BuildRule rule) {
          return (AndroidResourceRule)rule;
        }
  };

  private static ImmutableSet<AndroidResourceRule> findAllAndroidResourceDeps(BuildRule buildRule) {
    final ImmutableSet.Builder<AndroidResourceRule> androidResources = ImmutableSet.builder();
    AbstractDependencyVisitor visitor = new AbstractDependencyVisitor(buildRule) {

      @Override
      public boolean visit(BuildRule rule) {
        if (rule instanceof AndroidResourceRule) {
          AndroidResourceRule androidResourceRule = (AndroidResourceRule)rule;
          if (androidResourceRule.getRes() != null) {
            androidResources.add(androidResourceRule);
          }
        }

        // Only certain types of rules should be considered as part of this traversal.
        BuildRuleType type = rule.getType();
        return TRAVERSABLE_TYPES.contains(type);
      }

    };
    visitor.start();

    return androidResources.build();
  }

  @Override
  @Nullable
  public File getOutput() {
    if (pathToTextSymbolsFile != null) {
      return new File(pathToTextSymbolsFile);
    } else {
      return null;
    }
  }

  @Nullable
  public String getPathToTextSymbolsFile() {
    return pathToTextSymbolsFile;
  }

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
  public boolean isAndroidRule() {
    return true;
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    ImmutableSortedSet.Builder<String> resFiles = ImmutableSortedSet.naturalOrder();
    addResContents(resFiles);

    ImmutableSortedSet.Builder<String> assetsFiles = ImmutableSortedSet.naturalOrder();
    addAssetsContents(assetsFiles);

    return super.ruleKeyBuilder()
        .set("res", resFiles.build())
        .set("assets", assetsFiles.build());
  }

  public static Builder newAndroidResourceRuleBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractBuildRuleBuilder {

    @Nullable
    private String res = null;

    @Nullable
    private String rDotJavaPackage = null;

    @Nullable
    private String assetsDirectory = null;

    @Nullable
    private String manifestFile = null;

    private Builder() {}

    @Override
    public AndroidResourceRule build(Map<String, BuildRule> buildRuleIndex) {
      if ((res == null && rDotJavaPackage != null)
          || (res != null && rDotJavaPackage == null)) {
        throw new HumanReadableException("Both res and package must be set together in %s.",
            getBuildTarget().getFullyQualifiedName());
      }

      return new AndroidResourceRule(createBuildRuleParams(buildRuleIndex),
          res,
          rDotJavaPackage,
          assetsDirectory,
          manifestFile,
          new DefaultDirectoryTraverser());
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(String dep) {
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

    public Builder setRDotJavaPackage(String rDotJavaPackage) {
      this.rDotJavaPackage = rDotJavaPackage;
      return this;
    }

    public Builder setAssetsDirectory(String assets) {
      this.assetsDirectory = assets;
      return this;
    }

    public Builder setManifestFile(String manifestFile) {
      this.manifestFile = manifestFile;
      return this;
    }
  }
}
