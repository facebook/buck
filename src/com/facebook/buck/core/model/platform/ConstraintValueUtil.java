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

package com.facebook.buck.core.model.platform;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;

/** Utilities to work with {@link AbstractConstraintValue} */
public class ConstraintValueUtil {

  private ConstraintValueUtil() {}

  /**
   * Buck does not allow duplicate {@code config_setting} in a set of {@code constraint_value} in
   * {@code config_setting} or {@code platform} rules.
   */
  public static void validateUniqueConstraintSettings(
      String ruleType,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      ImmutableSet<ConstraintValue> constraintValues) {
    HashMultimap<ConstraintSetting, ConstraintValue> valuesBySetting = HashMultimap.create();

    for (ConstraintValue constraintValue : constraintValues) {
      valuesBySetting.put(constraintValue.getConstraintSetting(), constraintValue);
    }

    for (Map.Entry<ConstraintSetting, Collection<ConstraintValue>> e :
        valuesBySetting.asMap().entrySet()) {
      ConstraintSetting constraintSetting = e.getKey();
      Collection<ConstraintValue> constraintValuesForSetting = e.getValue();
      if (constraintValuesForSetting.size() > 1) {
        throw new HumanReadableException(
            dependencyStack,
            "in %s rule %s: Duplicate constraint values detected: constraint_setting %s has %s",
            ruleType,
            buildTarget,
            constraintSetting.getBuildTarget(),
            constraintValuesForSetting.stream()
                .map(ConstraintValue::getBuildTarget)
                .sorted(Comparator.comparing(BuildTarget::toString))
                .collect(ImmutableList.toImmutableList()));
      }
    }
  }
}
