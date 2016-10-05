/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.junit.Test;

import java.nio.file.Paths;

public class CxxPrecompiledHeaderTest {

  @Test
  public void generatesPchStepShouldUseCorrectLang() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    Preprocessor preprocessorSupportingPch =
        new DefaultPreprocessor(CxxPlatformUtils.DEFAULT_PLATFORM.getCpp().resolve(resolver)) {
          @Override
          public boolean supportsPrecompiledHeaders() {
            return true;
          }
        };
    Compiler compiler =
        CxxPlatformUtils.DEFAULT_PLATFORM.getCxx().resolve(resolver);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    CxxPrecompiledHeader precompiledHeader = new CxxPrecompiledHeader(
        params,
        sourcePathResolver,
        Paths.get("foo.pch"),
        new PreprocessorDelegate(
            sourcePathResolver,
            CxxPlatformUtils.DEFAULT_DEBUG_PATH_SANITIZER,
            CxxPlatformUtils.DEFAULT_CONFIG.getHeaderVerification(),
            Paths.get("./"),
            preprocessorSupportingPch,
            PreprocessorFlags.builder().build(),
            CxxDescriptionEnhancer.frameworkPathToSearchPath(
                CxxPlatformUtils.DEFAULT_PLATFORM,
                sourcePathResolver),
            ImmutableList.<CxxHeaders>of()),
        new CompilerDelegate(
            sourcePathResolver,
            CxxPlatformUtils.DEFAULT_DEBUG_PATH_SANITIZER,
            compiler,
            CxxToolFlags.of()
        ),
        CxxToolFlags.of(),
        new FakeSourcePath("foo.h"),
        CxxSource.Type.C,
        CxxPlatformUtils.DEFAULT_DEBUG_PATH_SANITIZER);
    ImmutableList<Step> postBuildSteps = precompiledHeader.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());
    CxxPreprocessAndCompileStep step = Iterables.getOnlyElement(
        Iterables.filter(postBuildSteps, CxxPreprocessAndCompileStep.class));
    assertThat(
        "step that generates pch should have correct flags",
        step.getCommand(),
        hasItem(CxxSource.Type.C.getPrecompiledHeaderLanguage().get()));
  }

}
