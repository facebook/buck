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
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import java.io.IOException;

public class BuckCopyPasteProcessorTest extends BuckTestCase {

  private static final String FIXTURE = ".fixture";

  private void doTest(String pasteText, String expected, String... testdata) throws Exception {
    if (testdata != null && testdata.length > 0) {
      PsiFile[] files = myFixture.configureByFiles(testdata);
      if (files != null) {
        Application application = ApplicationManager.getApplication();

        for (PsiFile file : files) {
          String name = file.getName();
          if (name.endsWith(FIXTURE)) {
            // Rename, without the .fixture extension
            String shortName = name.substring(0, name.length() - FIXTURE.length());
            VirtualFile fixture = file.getVirtualFile();
            Exception[] inWriteAction = new Exception[1]; // we (may) set [0] in a lambda
            application.runWriteAction(
                () -> {
                  try {
                    fixture.rename(this, shortName);
                  } catch (IOException e) {
                    inWriteAction[0] = e;
                  }
                });
            if (inWriteAction[0] != null) {
              throw inWriteAction[0];
            }
          }
        }
      }
    } else {
      String testPath = "formatter/paste/environment.BUCK";
      myFixture.configureByFile(testPath);
    }

    BuckCopyPasteProcessor processor = new BuckCopyPasteProcessor();
    String actual =
        processor.preprocessOnPaste(
            getProject(), myFixture.getFile(), myFixture.getEditor(), pasteText, null);
    assertEquals(expected, actual);
  }

  public void testPasteSingleTarget1() throws Exception {
    doTest(" \n    //foo:test   \n  \n", "\"//foo:test\",");
  }

  public void testPasteSingleTarget2() throws Exception {
    doTest("//foo:test\n\n\n", "\"//foo:test\",");
  }

  public void testPasteSingleTarget3() throws Exception {
    doTest("foo:test", "\"//foo:test\",");
  }

  public void testPasteMultiLineTargets() throws Exception {
    doTest(
        "     //foo:test   \n\n   :another\n foo:bar\n",
        "\"//foo:test\",\n\":another\",\n\"//foo:bar\",");
  }

  public void testPasteMultiWithInvalidTarget() throws Exception {
    final String invalid = "\n  //first:one\nthisoneisinvalid   \n";
    doTest(invalid, invalid);
  }

  public void testDashInTargetName() throws Exception {
    doTest("//third-party/java/json:json", "\"//third-party/java/json:json\",");
  }

  // region BuckCopyPasteProcessor.formatPasteText examples

  private static final String EXPECTED = "\"//src/com/example/activity:activity\",";

  private static final String[] TESTDATA =
      new String[] {
        "com/example/activity/BUCK" + FIXTURE, "com/example/activity/MyFirstActivity.java"
      };

  public void testPasteImportStatement() throws Exception {
    doTest("import com.example.activity.MyFirstActivity", EXPECTED, TESTDATA);
  }

  public void testPastePackageStatement() throws Exception {
    doTest("package com.example.activity;", EXPECTED, TESTDATA);
  }

  public void testPasteShortClassname() throws Exception {
    // This is not actually one of the examples. This will only work if there is one and only one
    // match to the short name.
    doTest("MyFirstActivity", EXPECTED, TESTDATA);
  }

  public void testPasteQualifiedClassname() throws Exception {
    doTest("com.example.activity.MyFirstActivity", EXPECTED, TESTDATA);
  }

  public void testPasteBuckPath() throws Exception {
    doTest("/src/com/example/activity/BUCK", EXPECTED, TESTDATA);
  }

  public void testPasteTarget() throws Exception {
    doTest("//apps/myapp:app", "\"//apps/myapp:app\",");
  }

  // endregion BuckCopyPasteProcessor.formatPasteText examples
}
