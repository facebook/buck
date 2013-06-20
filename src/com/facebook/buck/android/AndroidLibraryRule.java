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

import com.facebook.buck.java.AnnotationProcessingParams;
import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.RuleKey;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Set;

public class AndroidLibraryRule extends DefaultJavaLibraryRule {

  /**
   * Manifest to associate with this rule. Ultimately, this will be used with the upcoming manifest
   * generation logic.
   */
  private final Optional<String> manifestFile;

  @VisibleForTesting
  public AndroidLibraryRule(BuildRuleParams buildRuleParams,
      Set<String> srcs,
      Set<SourcePath> resources,
      Optional<String> proguardConfig,
      JavacOptions javacOptions,
      Optional<String> manifestFile) {
    super(buildRuleParams,
        srcs,
        resources,
        proguardConfig,
        /* exportDeps */ false,
        javacOptions);
    this.manifestFile = Preconditions.checkNotNull(manifestFile);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.ANDROID_LIBRARY;
  }

  public Optional<String> getManifestFile() {
    return manifestFile;
  }

  @Override
  public boolean isAndroidRule() {
    return true;
  }

  @Override
  protected RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
     return super.appendToRuleKey(builder)
         .set("manifest", manifestFile.orNull());
  }

  @Override
  protected List<String> getInputsToCompareToOutput() {
    if (manifestFile.isPresent()) {
      return ImmutableList.<String>builder()
          .addAll(super.getInputsToCompareToOutput())
          .add(manifestFile.get())
          .build();
    } else {
      return super.getInputsToCompareToOutput();
    }
  }

  public static Builder newAndroidLibraryRuleBuilder() {
    return new Builder();
  }

  public static class Builder extends DefaultJavaLibraryRule.Builder {
    private Optional<String> manifestFile = Optional.absent();

    @Override
    public AndroidLibraryRule build(BuildRuleResolver ruleResolver) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);
      AnnotationProcessingParams processingParams =
          annotationProcessingBuilder.build(ruleResolver);
      javacOptions.setAnnotationProcessingData(processingParams);

      return new AndroidLibraryRule(
          buildRuleParams,
          srcs,
          resources,
          proguardConfig,
          javacOptions.build(),
          manifestFile);
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
    public AndroidLibraryRule.Builder addSrc(String src) {
      return (AndroidLibraryRule.Builder)super.addSrc(src);
    }

    @Override
    public Builder addVisibilityPattern(BuildTargetPattern visibilityPattern) {
      super.addVisibilityPattern(visibilityPattern);
      return this;
    }

    @Override
    public AnnotationProcessingParams.Builder getAnnotationProcessingBuilder() {
      return annotationProcessingBuilder;
    }

    public Builder setManifestFile(Optional<String> manifestFile) {
      this.manifestFile = Preconditions.checkNotNull(manifestFile);
      return this;
    }

  }
}
