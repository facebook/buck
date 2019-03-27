/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.model;

import com.facebook.buck.core.model.impl.JsonTargetConfigurationSerializer;

public class TargetConfigurationSerializerForTests implements TargetConfigurationSerializer {

  @Override
  public String serialize(TargetConfiguration targetConfiguration) {
    return new JsonTargetConfigurationSerializer().serialize(targetConfiguration);
  }

  @Override
  public TargetConfiguration deserialize(String rawValue) {
    return EmptyTargetConfiguration.INSTANCE;
  }

  public static TargetConfigurationSerializer create() {
    return new TargetConfigurationSerializerForTests();
  }
}
