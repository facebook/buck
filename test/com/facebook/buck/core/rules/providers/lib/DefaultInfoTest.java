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

package com.facebook.buck.core.rules.providers.lib;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.lang.reflect.InvocationTargetException;
import org.junit.Test;

public class DefaultInfoTest {
  @Test
  public void canInstantiateDefaultInfo()
      throws IllegalAccessException, InstantiationException, InvocationTargetException {
    DefaultInfo defaultInfo =
        DefaultInfo.PROVIDER.createInfo(SkylarkDict.empty(), ImmutableSet.of());
    assertEquals(SkylarkDict.empty(), defaultInfo.namedOutputs());
    assertEquals(ImmutableSet.of(), defaultInfo.defaultOutputs());
  }

  @Test
  public void hasReasonableNameForKey() {
    assertEquals("DefaultInfo", DefaultInfo.PROVIDER.getKey().toString());
  }
}
