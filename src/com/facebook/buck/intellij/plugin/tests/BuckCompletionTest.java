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

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.util.ArrayUtil;

public class BuckCompletionTest extends BuckTestCase {

  private static final String[] NOTHING = ArrayUtil.EMPTY_STRING_ARRAY;

  private void doTest(String... variants) {
    myFixture.testCompletionVariants("completion/" + getTestName(false) + "/before.BUCK", variants);
  }

  private void doTestSingleVariant() {
    myFixture.configureByFile("completion/" + getTestName(false) + "/before.BUCK");
    final LookupElement[] variants = myFixture.completeBasic();
    assertNull(variants);
    myFixture.checkResultByFile("completion/" + getTestName(false) + "/after.BUCK");
  }

  public void testKeywords1() {
    doTestSingleVariant();
  }

  public void testKeywords2() {
    doTestSingleVariant();
  }

  public void testInString() {
    doTest(NOTHING);
  }

  public void testVariants() {
    doTest("android_build_config", "android_binary");
  }
}
