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

@org.junit.Ignore
public class BuckCopyPasteProcessorTest extends BuckTestCase {

  private static final String FIXTURE = ".fixture";
  private static final String BUCK = "BUCK";
  private static final String DOT_BUCK = "." + BUCK;

  private void doTest(String pasteText, String expected, String... testdata) throws Exception {
    if (testdata != null && testdata.length > 0) {
      PsiFile[] files = myFixture.configureByFiles(testdata);
      if (files != null) {
        Application application = ApplicationManager.getApplication();

        for (PsiFile file : files) {
          String name = file.getName();
          String newName = maybeRenameFixture(name);
          if (name != newName) {
            // Rename, without the .fixture extension
            VirtualFile fixture = file.getVirtualFile();
            Exception[] inWriteAction = new Exception[1]; // we (may) set [0] in a lambda
            application.runWriteAction(
                () -> {
                  try {
                    fixture.rename(this, newName);
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

  private static String maybeRenameFixture(String name) {
    if (name.endsWith(FIXTURE)) {
      return name.substring(0, name.length() - FIXTURE.length());
    }

    if (name.endsWith(DOT_BUCK)) {
      return BUCK;
    }

    return name;
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

  private static final String RAW_TARGET = "//src/com/example/activity:activity";
  private static final String WRAPPED_TARGET = "\"" + RAW_TARGET + "\",";
  private static final String UNQUALIFIED = "MyFirstActivity";

  private static final String[] TESTDATA =
      new String[] {
        "com/example/activity/BUCK" + FIXTURE, "com/example/activity/MyFirstActivity.java"
      };

  public void testPasteImportStatement() throws Exception {
    doTest("import com.example.activity.MyFirstActivity", WRAPPED_TARGET, TESTDATA);
  }

  public void testPastePackageStatement() throws Exception {
    doTest("package com.example.activity;", WRAPPED_TARGET, TESTDATA);
  }

  public void testPasteShortClassname() throws Exception {
    // This is not actually one of the examples. This test will only work if there is one and
    // only one match to the short name.

    doTest(UNQUALIFIED, WRAPPED_TARGET, TESTDATA);
  }

  public void testPasteQualifiedClassname() throws Exception {
    doTest("com.example.activity.MyFirstActivity", WRAPPED_TARGET, TESTDATA);
  }

  public void testPasteBuckPath() throws Exception {
    doTest("/src/com/example/activity/BUCK", WRAPPED_TARGET, TESTDATA);
  }

  public void testPasteTarget() throws Exception {
    doTest("//apps/myapp:app", "\"//apps/myapp:app\",");
  }

  // endregion BuckCopyPasteProcessor.formatPasteText examples

  // region Context sensitivity tests

  // 1. we should only transform text if the cursor is in a deps or visibility array AND
  // 2. if the cursor is in whitespace or within an empty string

  public void testUnqualifiedContext() throws Exception {
    // caret to left of double-quoted string
    doTest(
        UNQUALIFIED,
        WRAPPED_TARGET,
        "com/example/activity/left.BUCK",
        "com/example/activity/MyFirstActivity.java");

    // caret in empty double-quoted string
    doTest(
        UNQUALIFIED,
        RAW_TARGET,
        "com/example/activity/in.BUCK",
        "com/example/activity/MyFirstActivity.java");

    // caret under comma after double-quoted string
    doTest(
        UNQUALIFIED,
        UNQUALIFIED,
        "com/example/activity/right.BUCK",
        "com/example/activity/MyFirstActivity.java");

    // caret in populated double-quoted string
    doTest(
        UNQUALIFIED,
        UNQUALIFIED,
        "com/example/activity/nonempty.BUCK",
        "com/example/activity/MyFirstActivity.java");
  }

  public void testQualifiedContext() throws Exception {
    final String qualified = "//apps/myapp:app";
    final String wrapped = "\"//apps/myapp:app\",";

    // caret to left of double-quoted string
    doTest(qualified, wrapped, "com/example/activity/left.BUCK");

    // caret to left of non-empty, double-quoted string
    doTest(qualified, wrapped, "com/example/activity/left_of_nonempty_string.BUCK");

    // caret in empty double-quoted string
    doTest(qualified, qualified, "com/example/activity/in.BUCK");

    // caret under comma after double-quoted string
    doTest(qualified, qualified, "com/example/activity/right.BUCK");

    // caret in populated double-quoted string
    doTest(qualified, qualified, "com/example/activity/nonempty.BUCK");
  }

  // endregion Context sensitivity tests
}
