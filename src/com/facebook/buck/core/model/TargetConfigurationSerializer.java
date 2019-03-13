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

/**
 * Allows to convert {@link TargetConfiguration} to and from a string.
 *
 * <p>This should be used when a {@link TargetConfiguration} needs to passed externally.
 */
public interface TargetConfigurationSerializer {

  /** Converts {@link TargetConfiguration} to string. */
  String serialize(TargetConfiguration targetConfiguration);

  /** Creates {@link TargetConfiguration} from the provided text representation. */
  TargetConfiguration deserialize(String rawValue);
}
