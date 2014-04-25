/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

public class ClassNameFilterTest {
  @Test
  public void testFiltering() {
    ClassNameFilter filter = ClassNameFilter.fromConfiguration(ImmutableList.of(
        "^org/acra/",
        "^org/tukaani/",
        "/FbInjector^",
        "^com/facebook/build/Config^",
        "/nodex/"));

    assertTrue(filter.matches("org/acra/Reporter"));
    assertTrue(filter.matches("org/tukaani/Decoder$State"));
    assertTrue(filter.matches("com/facebook/inject/FbInjector"));
    assertTrue(filter.matches("com/facebook/build/Config"));
    assertTrue(filter.matches("com/facebook/nodex/Splash"));
    assertFalse(filter.matches("borg/acra/Reporter"));
    assertFalse(filter.matches("worg/tukaani/Decoder"));
    assertFalse(filter.matches("com/facebook/inject/FbInjectorImpl"));
    assertFalse(filter.matches("com/facebook/inject/FbInjector^"));
    assertFalse(filter.matches("com/facebook/build/Configs"));
    assertFalse(filter.matches("dcom/facebook/build/Config"));
    assertFalse(filter.matches("com/facebook/fake/build/Config"));
    assertFalse(filter.matches("com/facebook/modex/Splash"));
  }
}
