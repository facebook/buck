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

package com.facebook.buck.intellij.ideabuck.actions.select;

import com.facebook.buck.intellij.ideabuck.PlatformTestCaseWithBuckCells;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckIdentifier;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import java.nio.file.Path;
import java.util.Optional;

public class SelectedBuckLineMarkerContributorTest extends PlatformTestCaseWithBuckCells {

  private PsiElement getPsiRoot(Path path) {
    return Optional.of(asVirtualFile(path))
        .map(file -> PsiManager.getInstance(getProject()).findFile(file))
        .map(psiFile -> psiFile.getFirstChild())
        .get();
  }

  public void testValidGetInfo() {
    String content = "fb_java_library(\n" + "name = \"testing\"\n" + ")";
    Path path = createFileInDefaultCell("foo/BUCK", content);
    BuckIdentifier identifier = PsiTreeUtil.findChildOfType(getPsiRoot(path), BuckIdentifier.class);
    PsiElement leaf = identifier.getFirstChild();
    assertNotNull(new SelectedBuckLineMarkerContributor().getInfo(leaf));
  }

  public void testWrongElementGetInfo() {
    String content = "fb_java_library(\n" + "name = \"testing\"\n" + ")";
    Path path = createFileInDefaultCell("foo/BUCK", content);
    BuckIdentifier identifier = PsiTreeUtil.findChildOfType(getPsiRoot(path), BuckIdentifier.class);
    assertNull(new SelectedBuckLineMarkerContributor().getInfo(identifier));
  }

  public void testNoNameGetInfo() {
    String content = "fb_java_library(\n" + "deps = [\"PUBLIC\"]\n" + ")";
    Path path = createFileInDefaultCell("foo/BUCK", content);
    BuckIdentifier identifier = PsiTreeUtil.findChildOfType(getPsiRoot(path), BuckIdentifier.class);
    PsiElement leaf = identifier.getFirstChild();
    assertNull(new SelectedBuckLineMarkerContributor().getInfo(leaf));
  }

  public void testIdentifierWithDotGetInfo() {
    String content = "fb_native.genrule(\n" + "name = \"testing\"\n" + ")";
    Path path = createFileInDefaultCell("foo/BUCK", content);
    BuckIdentifier[] identifiers =
        PsiTreeUtil.findChildrenOfType(getPsiRoot(path), BuckIdentifier.class)
            .toArray(new BuckIdentifier[2]);
    PsiElement leafBeforeDot = identifiers[0].getFirstChild();
    PsiElement leafAfterDot = identifiers[1].getFirstChild();
    assertNotNull(new SelectedBuckLineMarkerContributor().getInfo(leafBeforeDot));
    assertNull(new SelectedBuckLineMarkerContributor().getInfo(leafAfterDot));
  }
}
