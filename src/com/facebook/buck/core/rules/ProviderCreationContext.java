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
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * The context given to {@link
 * LegacyProviderCompatibleDescription#createProviders(ProviderCreationContext, BuildTarget,
 * BuildRuleArg)}.
 *
 * <p>This is to be a subset of {@link BuildRuleCreationContextWithTargetGraph}, plus the {@link
 * ProviderInfoCollection} of all the dependencies.
 */
@BuckStyleValue
public interface ProviderCreationContext {

  /** @return {@link ProviderInfoCollection} of the parse time dependencies */
  ImmutableMap<BuildTarget, ProviderInfoCollection> dependencies();

  /** @return the {@link ProjectFilesystem} for the rule */
  ProjectFilesystem getProjectFilesystem();

  static ProviderCreationContext of(
      Map<? extends BuildTarget, ? extends ProviderInfoCollection> dependencies,
      ProjectFilesystem projectFilesystem) {
    return ImmutableProviderCreationContext.of(dependencies, projectFilesystem);
  }
}
