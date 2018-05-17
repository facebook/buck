/*
 * Copyright 2016-present Facebook, Inc.
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
// Copyright 2004-present Facebook. All Rights Reserved.

package com.facebook.buck.testrunner;

import com.android.ddmlib.IShellEnabledDevice;
import com.android.ddmlib.testrunner.ITestRunListener;
import com.android.ddmlib.testrunner.InstrumentationResultParser;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.facebook.buck.android.TestDevice;
import java.lang.reflect.Field;
import java.util.ArrayList;
import org.junit.Test;

/** Tests {@link InstrumentationTestRunner} */
public class InstrumentationTestRunnerTest {
  /** Just verifies the reflection is legit */
  @Test
  public void testSetTrimLinesHappyPath() throws Throwable {
    IShellEnabledDevice shellEnabledDevice = new TestDevice();
    RemoteAndroidTestRunner runner =
        new RemoteAndroidTestRunner("foobar", "blah", shellEnabledDevice);

    Field field = RemoteAndroidTestRunner.class.getDeclaredField("mParser");
    field.setAccessible(true);
    field.set(runner, new InstrumentationResultParser("fooBar", new ArrayList<ITestRunListener>()));

    InstrumentationTestRunner.setTrimLine(runner, true);
  }
}
