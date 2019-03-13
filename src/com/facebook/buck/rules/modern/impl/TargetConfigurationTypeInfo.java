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

package com.facebook.buck.rules.modern.impl;

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;

/** TypeInfo for TargetConfiguration values. */
public class TargetConfigurationTypeInfo implements ValueTypeInfo<TargetConfiguration> {
  public static final ValueTypeInfo<TargetConfiguration> INSTANCE =
      new TargetConfigurationTypeInfo();

  @Override
  public <E extends Exception> void visit(TargetConfiguration value, ValueVisitor<E> visitor)
      throws E {
    visitor.visitTargetConfiguration(value);
  }

  @Override
  public <E extends Exception> TargetConfiguration create(ValueCreator<E> creator) throws E {
    return creator.createTargetConfiguration();
  }
}
