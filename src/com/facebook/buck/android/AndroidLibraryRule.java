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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AnnotationProcessingParams;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DefaultJavaLibraryRule;
import com.google.common.annotations.VisibleForTesting;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

public class AndroidLibraryRule extends DefaultJavaLibraryRule {

  /**
   * Manifest to associate with this rule. Ultimately, this will be used with the upcoming manifest
   * generation logic.
   */
  @Nullable
  private final String manifestFile;

  @VisibleForTesting
  public AndroidLibraryRule(BuildRuleParams buildRuleParams,
      Set<String> srcs,
      Set<String> resources,
      @Nullable String proguardConfig,
      AnnotationProcessingParams annotationProcessingParams,
      @Nullable String manifestFile,
      String sourceLevel,
      String targetLevel) {
    super(buildRuleParams,
        srcs,
        resources,
        proguardConfig,
        annotationProcessingParams,
        /* exportDeps */ false,
        sourceLevel,
        targetLevel);
    this.manifestFile = manifestFile;
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.ANDROID_LIBRARY;
  }

  @Nullable
  public String getManifestFile() {
    return manifestFile;
  }

  @Override
  public boolean isAndroidRule() {
    return true;
  }

  public static Builder newAndroidLibraryRuleBuilder() {
    return new Builder();
  }

  public static class Builder extends DefaultJavaLibraryRule.Builder {

    @Nullable
    private String manifestFile = null;

    @Override
    public AndroidLibraryRule build(Map<String, BuildRule> buildRuleIndex) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(buildRuleIndex);
      AnnotationProcessingParams processingParams =
          annotationProcessingBuilder.build(buildRuleIndex);

      return new AndroidLibraryRule(
          buildRuleParams,
          srcs,
          resources,
          proguardConfig,
          processingParams,
          manifestFile,
          sourceLevel,
          targetLevel);
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

    public Builder setManifestFile(String manifestFile) {
      this.manifestFile = manifestFile;
      return this;
    }

  }
}
