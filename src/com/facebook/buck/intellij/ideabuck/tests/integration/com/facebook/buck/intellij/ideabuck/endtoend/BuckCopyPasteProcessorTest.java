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

package com.facebook.buck.intellij.ideabuck.endtoend;

import com.facebook.buck.intellij.ideabuck.format.BuckCopyPasteProcessor;

public class BuckCopyPasteProcessorTest extends BuckTestCase {

  private void doTest(String pasteText, String expected) {
    String testPath = "formatter/paste/environment.BUCK";
    BuckCopyPasteProcessor processor = new BuckCopyPasteProcessor();
    myFixture.configureByFile(testPath);
    String actual =
        processor.preprocessOnPaste(
            getProject(), myFixture.getFile(), myFixture.getEditor(), pasteText, null);

    assertEquals(actual, expected);
  }

  public void testPasteSingleTarget1() {
    doTest(" \n    //foo:test   \n  \n", "\"//foo:test\",");
  }

  public void testPasteSingleTarget2() {
    doTest("//foo:test\n\n\n", "\"//foo:test\",");
  }

  public void testPasteSingleTarget3() {
    doTest("foo:test", "\"//foo:test\",");
  }

  public void testPasteMultiLineTargets() {
    doTest(
        "     //foo:test   \n\n   :another\n foo:bar\n",
        "\"//foo:test\",\n':another',\n\"//foo:bar\",");
  }

  public void testPasteMultiWithInvalidTarget() {
    doTest("\n  //first:one\nthisoneisinvalid   \n", "\n  //first:one\nthisoneisinvalid   \n");
  }
}
