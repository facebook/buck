/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.test.TestRunningOptions;
import com.facebook.buck.test.selectors.TestSelectorList;
import java.util.Optional;
import org.junit.Test;

public class AndroidInstrumentationTestTest {
  @Test
  public void testFilterBasics() {
    assertEquals(
        Optional.<String>empty(),
        AndroidInstrumentationTest.getFilterString(TestRunningOptions.builder().build()));

    assertEquals(
        Optional.of("FooBar#method"),
        AndroidInstrumentationTest.getFilterString(
            TestRunningOptions.builder()
                .setTestSelectorList(
                    TestSelectorList.builder().addRawSelectors("FooBar#method").build())
                .build()));

    assertEquals(
        Optional.of("com.foo.FooBar"),
        AndroidInstrumentationTest.getFilterString(
            TestRunningOptions.builder()
                .setTestSelectorList(
                    TestSelectorList.builder().addRawSelectors("com.foo.FooBar").build())
                .build()));
  }
}
