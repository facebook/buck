/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.rules.type.BuildRuleType;
import com.facebook.buck.core.rules.type.impl.BuildRuleTypeFactory;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

public class DescriptionCache {

  private static final LoadingCache<Class<? extends Description<?>>, BuildRuleType>
      BUILD_RULE_TYPES_BY_CLASS =
          CacheBuilder.newBuilder()
              .build(
                  new CacheLoader<Class<? extends Description<?>>, BuildRuleType>() {
                    @Override
                    public BuildRuleType load(Class<? extends Description<?>> key) {
                      return BuildRuleTypeFactory.fromClassName(key);
                    }
                  });

  /** @return The {@link BuildRuleType} being described. */
  public static BuildRuleType getBuildRuleType(Class<? extends Description<?>> descriptionClass) {
    return BUILD_RULE_TYPES_BY_CLASS.getUnchecked(descriptionClass);
  }

  @SuppressWarnings("unchecked")
  public static BuildRuleType getBuildRuleType(Description<?> description) {
    return getBuildRuleType((Class<? extends Description<?>>) description.getClass());
  }
}
