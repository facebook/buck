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

package com.facebook.buck.features.project.intellij.aggregation;

import com.facebook.buck.features.project.intellij.model.IjModuleType;
import java.util.HashMap;
import java.util.Map;

public class AggregationContext {

  /**
   * Contains arbitrary information that defines the type of the module.
   *
   * <p>This information is used to distinguish modules of different types. If two modules contain
   * the same data in module info they can be aggregated into one module.
   *
   * <p>Build targets can provide different data depending on their type. For example, Java modules
   * can put Java language level in this map to avoid aggregating modules with different language
   * levels into one module.
   */
  private final Map<String, Object> aggregationData = new HashMap<>();

  private IjModuleType moduleType = IjModuleType.UNKNOWN_MODULE;

  public void addAggregationKey(String key, Object value) {
    aggregationData.put(key, value);
  }

  public String getAggregationTag() {
    return aggregationData.toString();
  }

  public IjModuleType getModuleType() {
    return moduleType;
  }

  public void setModuleType(IjModuleType moduleType) {
    if (moduleType.hasHigherPriorityThan(this.moduleType)) {
      this.moduleType = moduleType;
    }
  }

  public void finishModuleCreation() {
    aggregationData.put(AggregationKeys.MODULE_TYPE, moduleType);
  }
}
