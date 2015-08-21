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

import com.intellij.openapi.actionSystem.IdeActions;

public class BuckCommenterTest extends BuckTestCase {

  private void doTest(String actionId) {
    myFixture.configureByFile("commenter/" + getTestName(false) + "/before.BUCK");
    myFixture.performEditorAction(actionId);
    myFixture.checkResultByFile("commenter/" + getTestName(false) + "/after.BUCK", true);
  }

  public void testLineCommenter1() {
    doTest(IdeActions.ACTION_COMMENT_LINE);
  }

  public void testLineCommenter2() {
    doTest(IdeActions.ACTION_COMMENT_LINE);
  }

}
