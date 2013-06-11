/*
 * Copyright 2012-present Facebook, Inc.
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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.util.Map;

public class AndroidLibraryRuleTest {

  @Test
  public void testGetInputsToCompareToOuts() {
    AndroidLibraryRule androidLibraryRuleBuilderFoo = getAndroidLibraryRuleFoo();
    AndroidLibraryRule androidLibraryRuleBuilderBar = getAndroidLibraryRuleBar();
    BuildContext context = createMock(BuildContext.class);
    replay(context);

    MoreAsserts.assertListEquals(
        "getInputsToCompareToOutput() should include manifest and src.",
        ImmutableList.of(
            "java/src/com/foo/Foo.java",
            "java/src/com/foo/AndroidManifest.xml"),
        androidLibraryRuleBuilderFoo.getInputsToCompareToOutput(context));

    MoreAsserts.assertListEquals(
        "getInputsToCompareToOutput() should include only src.",
        ImmutableList.of(
            "java/src/com/bar/Bar.java"),
        androidLibraryRuleBuilderBar.getInputsToCompareToOutput(context));
  }

  private AndroidLibraryRule getAndroidLibraryRuleFoo() {
    Map<String, BuildRule> buildRuleIndex = ImmutableMap.of();
    return AndroidLibraryRule
        .newAndroidLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/foo:foo"))
        .addSrc("java/src/com/foo/Foo.java")
        .setManifestFile((Optional.of("java/src/com/foo/AndroidManifest.xml")))
        .build(buildRuleIndex);
  }

  private AndroidLibraryRule getAndroidLibraryRuleBar() {
    Map<String, BuildRule> buildRuleIndex = ImmutableMap.of();
    return AndroidLibraryRule
        .newAndroidLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/bar:bar"))
        .addSrc("java/src/com/bar/Bar.java")
        .setManifestFile((Optional.<String>absent()))
        .build(buildRuleIndex);
  }
}
