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

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.Nullable;

public class BuckFormatterTest extends BuckTestCase {

  public void testSimple1(){
    doTest();
  }

  public void testSimple2(){
    doTest();
  }

  public void testSimple3(){
    doTest();
  }

  public void testSimple4(){
    doTest();
  }

  public void testSimple5(){
    doTest();
  }

  public void testError(){
    doTest();
  }

  public void testEnterAfterComma1() {
    doTestEnter();
  }

  public void testEnterAfterComma2() {
    doTestEnter();
  }

  public void testEnterAfterComma3() {
    doTestEnter();
  }

  public void testEnterAfterComma4() {
    doTestEnter();
  }

  public void testEnterBeforeLBracket1() {
    doTestEnter();
  }

  public void testEnterAfterLBracket1() {
    doTestEnter();
  }

  public void testEnterAfterLBracket2() {
    doTestEnter();
  }

  public void testEnterAfterRBracket1() {
    doTestEnter();
  }

  public void testEnterAfterRBracket2() {
    doTestEnter();
  }

  public void testEnterBeforeLParentheses1() {
    doTestEnter();
  }

  public void testEnterAfterLParentheses1() {
    doTestEnter();
  }

  public void testEnterAfterLParentheses2() {
    doTestEnter();
  }

  public void testEnterAfterRParentheses1() {
    doTestEnter();
  }

  public void doTest() {
    doTest(null);
  }

  public void doTestEnter() {
    doTest('\n');
  }

  public void doTest(@Nullable Character c) {
    String testName = getTestName(true);
    myFixture.configureByFile("formatter/" + getTestName(false) + "/before.BUCK");
    doTest(c, testName);
    assertSameLinesWithFile(getTestDataPath() + "/formatter/" + getTestName(true) +
        "/after.BUCK", myFixture.getFile().getText());
  }

  private String doTest(@Nullable Character c, String testName) {
    if (c == null) {
      ApplicationManager.getApplication().runWriteAction(new Runnable() {
        @Override
        public void run() {
          CodeStyleManager.getInstance(getProject()).reformat(myFixture.getFile());
        }
      });
    } else {
      myFixture.type(c);
    }
    return String.format("%s-after.BUCK", testName);
  }
}
