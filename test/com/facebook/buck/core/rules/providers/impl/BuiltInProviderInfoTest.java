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
package com.facebook.buck.core.rules.providers.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.core.rules.providers.annotations.ImmutableInfo;
import java.lang.reflect.InvocationTargetException;
import org.junit.Test;

public class BuiltInProviderInfoTest {

  @ImmutableInfo(args = {"str", "my_info"})
  public abstract static class SomeInfo extends BuiltInProviderInfo<SomeInfo> {
    public static final BuiltInProvider<SomeInfo> PROVIDER =
        BuiltInProvider.of(ImmutableSomeInfo.class);

    public abstract String str();

    public abstract int myInfo();
  }

  @ImmutableInfo(args = {"str"})
  public abstract static class OtherInfo extends BuiltInProviderInfo<OtherInfo> {
    public static final BuiltInProvider<OtherInfo> PROVIDER =
        BuiltInProvider.of(ImmutableOtherInfo.class);

    public abstract String str();
  }

  @Test
  public void someInfoProviderCreatesCorrectInfo()
      throws IllegalAccessException, InstantiationException, InvocationTargetException {
    SomeInfo someInfo1 = new ImmutableSomeInfo("a", 1);
    assertEquals("a", someInfo1.str());
    assertEquals(1, someInfo1.myInfo());

    SomeInfo someInfo2 = someInfo1.getProvider().createInfo("b", 2);
    assertEquals("b", someInfo2.str());
    assertEquals(2, someInfo2.myInfo());

    SomeInfo someInfo3 = SomeInfo.PROVIDER.createInfo("c", 3);
    assertEquals("c", someInfo3.str());
    assertEquals(3, someInfo3.myInfo());
  }

  @Test
  public void differentInfoInstanceProviderKeyEquals()
      throws IllegalAccessException, InstantiationException, InvocationTargetException {
    SomeInfo someInfo1 = new ImmutableSomeInfo("a", 1);

    SomeInfo someInfo2 = SomeInfo.PROVIDER.createInfo("b", 2);

    assertEquals(SomeInfo.PROVIDER.getKey(), someInfo1.getProvider().getKey());
    assertEquals(someInfo1.getProvider().getKey(), someInfo2.getProvider().getKey());
  }

  @Test
  public void differentInfoTypeProviderKeyNotEquals() {
    assertNotEquals(SomeInfo.PROVIDER.getKey(), OtherInfo.PROVIDER.getKey());
  }
}
