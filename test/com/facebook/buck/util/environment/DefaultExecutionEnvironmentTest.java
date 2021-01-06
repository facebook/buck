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

package com.facebook.buck.util.environment;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableMap;
import java.util.Properties;
import org.junit.Test;

public class DefaultExecutionEnvironmentTest {
  @Test
  public void getUsernameUsesSuppliedEnvironment() {
    String name = "TEST_USER_PLEASE_IGNORE";
    DefaultExecutionEnvironment environment =
        new DefaultExecutionEnvironment(ImmutableMap.of("USER", name), System.getProperties());
    assertEquals("Username should match test data.", name, environment.getUsername());
  }

  @Test
  public void getUsernameFallsBackToPropertyIfUnsetInEnvironment() {
    String name = "TEST_USER_PLEASE_IGNORE";
    Properties properties = new Properties();
    properties.setProperty("user.name", name);
    DefaultExecutionEnvironment environment =
        new DefaultExecutionEnvironment(ImmutableMap.of(), properties);
    assertEquals("Username should match test data.", name, environment.getUsername());
  }
}
