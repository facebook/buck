/*
 * Copyright 2012-present Facebook, Inc.
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
import com.google.common.collect.ImmutableSet;

import java.util.List;

public abstract class AbstractTestRuleFactory<T extends AbstractBuildRuleBuilder<?>>
    extends AbstractBuildRuleFactory<T> {

  @Override
  public T newInstance(BuildRuleFactoryParams params)
      throws NoSuchBuildTargetException {
    T builder = super.newInstance(params);

    // labels
    if (builder instanceof LabelsAttributeBuilder) {
      List<String> rawLabels = params.getOptionalListAttribute("labels");
      ImmutableSet.Builder<Label> labelBuilder = new ImmutableSet.Builder<>();
      for (String rawLabel : rawLabels) {
        Label label = new Label(rawLabel);
        labelBuilder.add(label);
      }
      ((LabelsAttributeBuilder)builder).setLabels(labelBuilder.build());
    }

    return builder;
  }
}
