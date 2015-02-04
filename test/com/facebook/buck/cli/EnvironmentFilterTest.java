/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

public class EnvironmentFilterTest {

  @Test
  public void testCleanEnvironment() {
    ImmutableMap.Builder<String, String> cleanEnvironmentBuilder = ImmutableMap.builder();
    cleanEnvironmentBuilder.put("TEST_ID", "42");
    cleanEnvironmentBuilder.put("TEST_ANOTHER_ID", "23");
    ImmutableMap<String, String> cleanEnvironment = cleanEnvironmentBuilder.build();
    assertEquals(
        cleanEnvironment,
        EnvironmentFilter.filteredEnvironment(cleanEnvironment, Platform.detect()));
  }

  @Test
  public void testAllDirtyEnvironment() {
    ImmutableMap.Builder<String, String> dirtyEnvironmentBuilder = ImmutableMap.builder();
    dirtyEnvironmentBuilder.put("TERM_SESSION_ID", "42");
    dirtyEnvironmentBuilder.put("BUCK_CLASSPATH", "/home/foo/bar");
    dirtyEnvironmentBuilder.put("CLASSPATH", "/home/foo/bar");
    dirtyEnvironmentBuilder.put("BUCK_BUILD_ID", "42");
    dirtyEnvironmentBuilder.put("CMD_DURATION", "42");
    ImmutableMap<String, String> dirtyEnvironment = dirtyEnvironmentBuilder.build();
    assertTrue(EnvironmentFilter.filteredEnvironment(
          dirtyEnvironment,
          Platform.detect()).isEmpty());
  }

  @Test
  public void testDirtyEnvironment() {
    ImmutableMap.Builder<String, String> dirtyEnvironmentBuilder = ImmutableMap.builder();
    dirtyEnvironmentBuilder.put("TERM_SESSION_ID", "42");
    dirtyEnvironmentBuilder.put("TEST_ID", "42");
    dirtyEnvironmentBuilder.put("TEST_ANOTHER_ID", "23");
    dirtyEnvironmentBuilder.put("BUCK_BUILD_ID", "42");
    ImmutableMap<String, String> dirtyEnvironment = dirtyEnvironmentBuilder.build();
    ImmutableMap<String, String> filteredEnvironment =
      EnvironmentFilter.filteredEnvironment(dirtyEnvironment, Platform.detect());
    assertEquals(2, filteredEnvironment.size());
    ImmutableMap.Builder<String, String> cleanEnvironmentBuilder = ImmutableMap.builder();
    cleanEnvironmentBuilder.put("TEST_ID", "42");
    cleanEnvironmentBuilder.put("TEST_ANOTHER_ID", "23");
    assertEquals(cleanEnvironmentBuilder.build(), filteredEnvironment);
  }

  @Test
  public void testWindowsPlatform() {
    ImmutableMap.Builder<String, String> environmentBuilder = ImmutableMap.builder();
    environmentBuilder.put("test_id", "42");
    environmentBuilder.put("Test_Another_Id", "23");
    environmentBuilder.put("TEST_PATH", "/home/foo/bar");
    ImmutableMap<String, String> filteredEnvironment =
      EnvironmentFilter.filteredEnvironment(environmentBuilder.build(), Platform.WINDOWS);
    assertTrue(filteredEnvironment.containsKey("TEST_ID"));
    assertTrue(filteredEnvironment.containsKey("TEST_ANOTHER_ID"));
    assertTrue(filteredEnvironment.containsKey("TEST_PATH"));
  }

  @Test
  public void testAllPlatform() {
    ImmutableMap.Builder<String, String> environmentBuilder = ImmutableMap.builder();
    environmentBuilder.put("test_id", "42");
    environmentBuilder.put("Test_Another_Id", "23");
    environmentBuilder.put("TEST_PATH", "/home/foo/bar");
    ImmutableMap<String, String> filteredEnvironment =
      EnvironmentFilter.filteredEnvironment(environmentBuilder.build(), Platform.LINUX);
    assertTrue(filteredEnvironment.containsKey("test_id"));
    assertTrue(filteredEnvironment.containsKey("Test_Another_Id"));
    assertTrue(filteredEnvironment.containsKey("TEST_PATH"));
  }
}
