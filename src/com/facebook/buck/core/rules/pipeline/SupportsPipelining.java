/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.core.rules.pipeline;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

/**
 * The steps required to build this rule locally can run more efficiently when executed immediately
 * after those of a dependency.
 *
 * @param <T> the type that is used to share build state between rules in the pipeline
 * @deprecated Rule pipelining couples rules in a way that makes it very easy to violate buck's
 *     assumptions and makes it nearly impossible for buck to understand and restrict what rules are
 *     doing.
 */
@Deprecated
public interface SupportsPipelining<T extends RulePipelineState> extends BuildRule {
  static boolean isSupported(BuildRule rule) {
    if (!(rule instanceof SupportsPipelining)) {
      return false;
    }

    SupportsPipelining<?> supportsPipelining = (SupportsPipelining<?>) rule;
    return supportsPipelining.useRulePipelining();
  }

  boolean useRulePipelining();

  @Nullable
  SupportsPipelining<T> getPreviousRuleInPipeline();

  ImmutableList<? extends Step> getPipelinedBuildSteps(
      BuildContext context, BuildableContext buildableContext, T state);

  RulePipelineStateFactory<T> getPipelineStateFactory();
}
