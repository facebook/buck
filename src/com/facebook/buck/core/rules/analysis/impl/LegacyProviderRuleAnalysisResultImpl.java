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

package com.facebook.buck.core.rules.analysis.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/**
 * An package private immutable implementation of {@link LegacyProviderRuleAnalysisResult} so
 * constructor is limited to package scope
 */
@BuckStyleValue
abstract class LegacyProviderRuleAnalysisResultImpl extends LegacyProviderRuleAnalysisResult {

  @Override
  public abstract BuildTarget getBuildTarget();

  /** @return a {@link ProviderInfoCollection} exported by the rule */
  @Override
  public abstract ProviderInfoCollection getProviderInfos();
}
