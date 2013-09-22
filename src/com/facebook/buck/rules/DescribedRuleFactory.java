/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.google.common.base.Preconditions;

/**
 * A factory for {@link DescribedRuleBuilder} instances. In order for Buck to register a new build
 * rule, it requires a factory method for builders of the rule which, in turn, contain a
 * {@link Buildable}. This class provides the factory for the builder of the Buildable (a Builder
 * Builder Builder if you will).
 *
 * @param <T> The constructor argument type returned by a {@link Description} of type T.
 */
public class DescribedRuleFactory<T> implements BuildRuleFactory<DescribedRuleBuilder<T>> {

  private final Description<T> description;

  public DescribedRuleFactory(Description<T> description) {
    this.description = Preconditions.checkNotNull(description);
  }

  @Override
  public DescribedRuleBuilder<T> newInstance(BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    return new DescribedRuleBuilder<>(description, params);
  }
}
