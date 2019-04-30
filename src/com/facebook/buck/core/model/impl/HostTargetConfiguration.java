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
package com.facebook.buck.core.model.impl;

import com.facebook.buck.core.model.TargetConfiguration;
import java.util.Objects;

/** Target configuration for the host platform. */
public class HostTargetConfiguration implements TargetConfiguration {
  public static final HostTargetConfiguration INSTANCE = new HostTargetConfiguration();

  private final int hashCode = Objects.hash(HostTargetConfiguration.class.getName());

  private HostTargetConfiguration() {}

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof HostTargetConfiguration;
  }
}
