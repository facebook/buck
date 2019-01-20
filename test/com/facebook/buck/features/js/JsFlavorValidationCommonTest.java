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

package com.facebook.buck.features.js;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.FlavorDomainException;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class JsFlavorValidationCommonTest {

  // Keeping separate map of class to instance objects only to have good names in test name
  // because @Parameterized.Parameters(name = "{0}") will call toString() which by default resolves
  // to unstable string and cause test tracking system to explode test names
  // NOTE: JUnit does not support callbacks to infer name from
  static final Map<Class<?>, Flavored> TEST_DATA =
      ImmutableMap.of(
          JsLibraryDescription.class, new JsLibraryDescription(),
          JsBundleDescription.class,
              new JsBundleDescription(new ToolchainProviderBuilder().build()));

  @Parameterized.Parameter public Class<?> description;

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Class<?>> getDescriptions() {
    return TEST_DATA.keySet();
  }

  private Flavored getTestInstance() {
    return TEST_DATA.get(description);
  }

  @Test
  public void testEmptyFlavors() {
    assertTrue(getTestInstance().hasFlavors(ImmutableSet.of()));
  }

  @Test
  public void testReleaseFlavor() {
    assertTrue(getTestInstance().hasFlavors(ImmutableSet.of(JsFlavors.RELEASE)));
  }

  @Test
  public void testAndroidFlavor() {
    assertTrue(getTestInstance().hasFlavors(ImmutableSet.of(JsFlavors.ANDROID)));
    assertTrue(getTestInstance().hasFlavors(ImmutableSet.of(JsFlavors.ANDROID, JsFlavors.RELEASE)));
  }

  @Test
  public void testIosFlavor() {
    assertTrue(getTestInstance().hasFlavors(ImmutableSet.of(JsFlavors.IOS)));
    assertTrue(getTestInstance().hasFlavors(ImmutableSet.of(JsFlavors.IOS, JsFlavors.RELEASE)));
  }

  @Test(expected = FlavorDomainException.class)
  public void testMultiplePlatforms() {
    getTestInstance().hasFlavors(ImmutableSet.of(JsFlavors.ANDROID, JsFlavors.IOS));
  }

  @Test
  public void testUnknownFlavors() {
    assertFalse(getTestInstance().hasFlavors(ImmutableSet.of(InternalFlavor.of("unknown"))));
    assertFalse(
        getTestInstance()
            .hasFlavors(ImmutableSet.of(InternalFlavor.of("unknown"), JsFlavors.RELEASE)));
  }
}
