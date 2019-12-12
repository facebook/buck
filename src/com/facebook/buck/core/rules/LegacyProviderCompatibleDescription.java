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

package com.facebook.buck.core.rules;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;

/**
 * Marks a {@link DescriptionWithTargetGraph} as compatible with {@link
 * com.facebook.buck.core.rules.providers.Provider}s.
 */
public interface LegacyProviderCompatibleDescription<T extends BuildRuleArg>
    extends DescriptionWithTargetGraph<T> {

  /**
   * @param context the {@link ProviderCreationContext} that the implementation has access to
   * @param buildTarget the current {@link BuildTarget}, with flavours
   * @param args A constructor argument, of type as returned by {@link #getConstructorArgType()}
   * @return the {@link ProviderInfoCollection} of this rule.
   */
  ProviderInfoCollection createProviders(
      ProviderCreationContext context, BuildTarget buildTarget, T args);
}
