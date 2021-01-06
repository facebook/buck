/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.description;

import com.facebook.buck.core.description.arg.BuildRuleArg;

/**
 * An instance {@link com.facebook.buck.core.description.RuleDescription} that has a more
 * complicated name. The common use case is {@link
 * com.facebook.buck.core.starlark.rule.SkylarkDescription}
 */
public interface RuleDescriptionWithInstanceName<T extends BuildRuleArg>
    extends RuleDescription<T> {
  /**
   * @return The user friendly name of a rule. e.g. cxx_binary, or //foo:baz.bzl:some_rule. See
   *     {@link
   *     com.facebook.buck.core.description.impl.DescriptionCache#getRuleType(BaseDescription)}
   */
  String getRuleName(T args);
}
