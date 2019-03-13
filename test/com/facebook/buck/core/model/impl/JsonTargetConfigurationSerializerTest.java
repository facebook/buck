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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.EmptyTargetConfiguration;
import org.junit.Test;

public class JsonTargetConfigurationSerializerTest {

  @Test
  public void emptyTargetConfigurationSerializesToString() {
    assertEquals(
        "{}", new JsonTargetConfigurationSerializer().serialize(EmptyTargetConfiguration.INSTANCE));
  }

  @Test
  public void emptyTargetConfigurationDeserializesFromString() {
    assertEquals(
        EmptyTargetConfiguration.INSTANCE,
        new JsonTargetConfigurationSerializer().deserialize("{}"));
  }
}
