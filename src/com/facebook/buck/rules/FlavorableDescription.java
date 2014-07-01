/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.util.ProjectFilesystem;

/**
 * {@link Description} that has flavors to register.
 */
public interface FlavorableDescription<T extends ConstructorArg> extends Description<T> {

  // TODO(simons): Currently, this API requires that implementations create Buildables for all
  // possible flavors, which can waste memory. Tighten up this API so that such Buildables are
  // created only on demand, while ensuring there is no risk that multiple BuildRules are created
  // for the same flavor.

  /**
   * Creates a {@link BuildRule} for each flavor for this {@link Description}. Each
   * {@link BuildRule} must be registered via the {@code ruleResolver}. Note that the
   * {@link BuildRule} for a flavor will not be built unless it ends up in the transitive deps of
   * the rules to be built.
   * @param arg used to create the described rule.
   * @param describedRule rule being flavored: has not been registered with ruleResolver yet.
   * @param projectFilesystem available to help construct a {@link BuildRuleParams}.
   * @param ruleKeyBuilderFactory available to help construct a {@link BuildRuleParams}.
   * @param ruleResolver with which new flavors introduced by this method should be registered.
   */
  public void registerFlavors(
      T arg,
      BuildRule describedRule,
      ProjectFilesystem projectFilesystem,
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      BuildRuleResolver ruleResolver);
}
