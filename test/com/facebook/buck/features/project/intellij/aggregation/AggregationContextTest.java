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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.features.project.intellij.model.IjModuleType;
import org.junit.Before;
import org.junit.Test;

public class AggregationContextTest {

  private AggregationContext context;

  @Before
  public void setUp() {
    context = new AggregationContext();
  }

  @Test
  public void testModuleTypeIsUnknownByDefault() {
    assertEquals(IjModuleType.UNKNOWN_MODULE, context.getModuleType());
  }

  @Test
  public void testUnknownTypeIsOverridden() {
    context.setModuleType(IjModuleType.JAVA_MODULE);
    assertEquals(IjModuleType.JAVA_MODULE, context.getModuleType());
  }

  @Test
  public void testJavaTypeIsOverridden() {
    context.setModuleType(IjModuleType.JAVA_MODULE);
    context.setModuleType(IjModuleType.ANDROID_MODULE);
    assertEquals(IjModuleType.ANDROID_MODULE, context.getModuleType());
  }

  @Test
  public void testAndroidTypeIsOverridden() {
    context.setModuleType(IjModuleType.ANDROID_MODULE);
    context.setModuleType(IjModuleType.ANDROID_RESOURCES_MODULE);
    assertEquals(IjModuleType.ANDROID_RESOURCES_MODULE, context.getModuleType());
  }

  @Test
  public void testAndroidResourcesTypeIsOverridden() {
    context.setModuleType(IjModuleType.ANDROID_RESOURCES_MODULE);
    context.setModuleType(IjModuleType.INTELLIJ_PLUGIN_MODULE);
    assertEquals(IjModuleType.INTELLIJ_PLUGIN_MODULE, context.getModuleType());
  }

  @Test
  public void testJavaTypeIsNotOverriden() {
    context.setModuleType(IjModuleType.JAVA_MODULE);
    context.setModuleType(IjModuleType.UNKNOWN_MODULE);
    assertEquals(IjModuleType.JAVA_MODULE, context.getModuleType());
  }

  @Test
  public void testAndroidTypeIsNotOverriden() {
    context.setModuleType(IjModuleType.ANDROID_MODULE);

    context.setModuleType(IjModuleType.UNKNOWN_MODULE);
    assertEquals(IjModuleType.ANDROID_MODULE, context.getModuleType());

    context.setModuleType(IjModuleType.JAVA_MODULE);
    assertEquals(IjModuleType.ANDROID_MODULE, context.getModuleType());
  }

  @Test
  public void testAndroidResourcesTypeIsNotOverriden() {
    context.setModuleType(IjModuleType.ANDROID_RESOURCES_MODULE);

    context.setModuleType(IjModuleType.UNKNOWN_MODULE);
    assertEquals(IjModuleType.ANDROID_RESOURCES_MODULE, context.getModuleType());

    context.setModuleType(IjModuleType.JAVA_MODULE);
    assertEquals(IjModuleType.ANDROID_RESOURCES_MODULE, context.getModuleType());

    context.setModuleType(IjModuleType.ANDROID_MODULE);
    assertEquals(IjModuleType.ANDROID_RESOURCES_MODULE, context.getModuleType());
  }

  @Test
  public void testIntellijPluginTypeIsNotOverriden() {
    context.setModuleType(IjModuleType.INTELLIJ_PLUGIN_MODULE);

    context.setModuleType(IjModuleType.UNKNOWN_MODULE);
    assertEquals(IjModuleType.INTELLIJ_PLUGIN_MODULE, context.getModuleType());

    context.setModuleType(IjModuleType.JAVA_MODULE);
    assertEquals(IjModuleType.INTELLIJ_PLUGIN_MODULE, context.getModuleType());

    context.setModuleType(IjModuleType.ANDROID_MODULE);
    assertEquals(IjModuleType.INTELLIJ_PLUGIN_MODULE, context.getModuleType());

    context.setModuleType(IjModuleType.ANDROID_RESOURCES_MODULE);
    assertEquals(IjModuleType.INTELLIJ_PLUGIN_MODULE, context.getModuleType());
  }
}
