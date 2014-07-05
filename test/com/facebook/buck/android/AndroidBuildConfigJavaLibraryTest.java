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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.util.Map;

public class AndroidBuildConfigJavaLibraryTest {

  @Test
  public void testAddToCollector() {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget).build();
    AndroidBuildConfigJavaLibrary buildConfigJavaLibrary = AndroidBuildConfigDescription
        .createBuildRule(
          params,
          "com.example.buck",
          /* useConstantExpressions */ false,
          /* constants */ ImmutableMap.<String, Object>of("foo", "bar"));

    AndroidPackageableCollector collector = new AndroidPackageableCollector(buildTarget);
    buildConfigJavaLibrary.addToCollector(collector);
    AndroidPackageableCollection collection = collector.build();
    assertEquals(
        ImmutableMap.of("com.example.buck", ImmutableMap.<String, Object>of("foo", "bar")),
        collection.buildConfigs);
  }

  @Test
  public void testBuildConfigHasCorrectProperties() {
    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//foo:bar").build();
    Map<String, Object> constants = ImmutableMap.<String, Object>of("KEY", "value");
    AndroidBuildConfigJavaLibrary buildConfigJavaLibrary = AndroidBuildConfigDescription
        .createBuildRule(
          params,
          "com.example.buck",
          /* useConstantExpressions */ false,
          constants);
    AndroidBuildConfig buildConfig = buildConfigJavaLibrary.getAndroidBuildConfig();
    assertEquals("com.example.buck", buildConfig.getJavaPackage());
    assertEquals(constants, buildConfig.getConstants());
  }
}
