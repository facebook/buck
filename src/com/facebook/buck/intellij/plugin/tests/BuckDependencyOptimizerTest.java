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

import com.intellij.codeInsight.actions.OptimizeImportsAction;
import com.intellij.ide.DataManager;

public class BuckDependencyOptimizerTest extends BuckTestCase {
  public void testSimple() {
    doTest();
  }

  public void testLintRule() {
    doTest();
  }

  private void doTest() {
    myFixture.configureByFile("dependencyOptimizer/" + getTestName(false) + "/before.BUCK");

    OptimizeImportsAction.actionPerformedImpl(
        DataManager.getInstance().getDataContext(myFixture.getEditor().getContentComponent()));

    myFixture.checkResultByFile("dependencyOptimizer/" + getTestName(true) + "/after.BUCK");
  }
}
