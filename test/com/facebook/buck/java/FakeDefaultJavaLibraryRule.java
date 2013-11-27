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

package com.facebook.buck.java;

import com.facebook.buck.android.DummyRDotJava;
import com.facebook.buck.android.JavaLibraryGraphEnhancer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;

import java.util.Set;

public class FakeDefaultJavaLibraryRule extends DefaultJavaLibraryRule {

  // TODO(mbolin): Find a way to make use of this field or delete it.
  @SuppressWarnings("unused")
  private final boolean ruleInputsAreCached;

  protected FakeDefaultJavaLibraryRule(BuildRuleParams buildRuleParams,
                                       Set<String> srcs,
                                       Set<SourcePath> resources,
                                       Optional<DummyRDotJava> optionalDummyRDotJava,
                                       Optional<String> proguardConfig,
                                       AnnotationProcessingParams annotationProcessingParams,
                                       Set<BuildRule> exportedDeps,
                                       boolean ruleInputsAreCached) {
    super(buildRuleParams,
        srcs,
        resources,
        optionalDummyRDotJava,
        proguardConfig,
        exportedDeps,
        JavacOptions.builder().setAnnotationProcessingData(annotationProcessingParams).build()
    );

    this.ruleInputsAreCached = ruleInputsAreCached;
  }

  public static FakeDefaultJavaLibraryRule.Builder newFakeJavaLibraryRuleBuilder() {
    return new FakeDefaultJavaLibraryRule.Builder();
  }

  public static class Builder extends DefaultJavaLibraryRule.Builder {
    private boolean ruleInputsAreCached;

    public Builder() {
      super(new FakeAbstractBuildRuleBuilderParams());
    }

    @Override
    public FakeDefaultJavaLibraryRule build(BuildRuleResolver ruleResolver) {
      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);
      AnnotationProcessingParams processingParams =
          annotationProcessingBuilder.build(ruleResolver);

      JavaLibraryGraphEnhancer.Result result =
          new JavaLibraryGraphEnhancer(buildTarget, buildRuleParams, params)
              .createBuildableForAndroidResources(
                  ruleResolver, /* createBuildableIfEmptyDeps */ false);

      return new FakeDefaultJavaLibraryRule(
          result.getBuildRuleParams(),
          srcs,
          resources,
          result.getOptionalDummyRDotJava(),
          proguardConfig,
          processingParams,
          getBuildTargetsAsBuildRules(ruleResolver, exportedDeps),
          ruleInputsAreCached);
    }

    public Builder setRuleInputsAreCached(boolean ruleInputsAreCached) {
      this.ruleInputsAreCached = ruleInputsAreCached;
      return this;
    }
  }
}
