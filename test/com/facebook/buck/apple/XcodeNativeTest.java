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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.TestSourcePath;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;

public class XcodeNativeTest {

  private BuildRuleParams params;

  @Before
  public void createFixtures() {
    params = new FakeBuildRuleParams(BuildTargetFactory.newInstance("//:xcode_native_test"));
  }

  @Test
  public void shouldPopulateFieldsFromArg() {
    XcodeNativeDescription.Arg arg =
        new XcodeNativeDescription().createUnpopulatedConstructorArg();
    arg.product = "libfoo.a";
    arg.targetGid = "0";
    arg.projectContainerPath = new TestSourcePath("foo.xcodeproj");
    XcodeNative xcodeNative = new XcodeNative(params, arg);

    assertEquals("libfoo.a", xcodeNative.getProduct());
    assertEquals("0", xcodeNative.getTargetGid());
    assertEquals(new TestSourcePath("foo.xcodeproj"), xcodeNative.getProjectContainerPath());

    assertEquals(Paths.get("buck-out/gen/libfoo.a"), xcodeNative.getPathToOutputFile());
  }
}
