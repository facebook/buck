/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.intellij.ideabuck.endtoend;

import com.facebook.buck.intellij.ideabuck.config.BuckExecutableSettingsProvider;
import com.google.common.io.Files;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

public class BuildifierFormattingTest extends BuckTestCase {

  private void formattingAction(PsiFile psiFile) {
    ApplicationManager.getApplication()
        .runWriteAction(
            new Runnable() {
              @Override
              public void run() {
                Collection<TextRange> textRanges = Collections.singleton(psiFile.getTextRange());
                CodeStyleManager.getInstance(getProject()).reformatText(psiFile, textRanges);
              }
            });
  }

  private void doTest(String executableContent, String before, String after) throws IOException {
    try {
      File buildifier = File.createTempFile("buildifier", "sh");
      Charset charset = Charset.defaultCharset();
      Files.write(executableContent.getBytes(charset), buildifier);
      buildifier.setExecutable(true, true);
      BuckExecutableSettingsProvider.getInstance(getProject())
          .setBuildifierExecutableOverride(Optional.of(buildifier.getAbsolutePath()));

      PsiFile psiFile = myFixture.configureByText("BUCK", before);
      Document document = PsiDocumentManager.getInstance(getProject()).getDocument(psiFile);
      assertNotNull(document);
      assertEquals("Sanity check: document initialized correctly", document.getText(), before);
      formattingAction(psiFile);
      assertEquals(document.getText(), after);
    } finally {
      BuckExecutableSettingsProvider.getInstance(getProject())
          .setBuildifierExecutableOverride(Optional.empty());
    }
  }

  public void testContentsChangedWhenBuildiferOutputsDifferentText() throws IOException {
    doTest("#!/usr/bin/env bash\n" + "echo bar\n" + "exit 0\n", "foo\n", "bar\n");
  }

  public void testContentsUnchangedWhenBuildiferOutputsNothing() throws IOException {
    doTest("#!/usr/bin/env bash\n" + "exit 0\n", "foo\n", "foo\n");
  }

  public void testContentsUnchangedWhenBuildiferExitsWithNonZeroCode() throws IOException {
    doTest("#!/usr/bin/env bash\n" + "echo failure message>&2\n" + "exit 1\n", "foo\n", "foo\n");
  }
}
