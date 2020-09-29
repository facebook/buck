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

package com.facebook.buck.intellij.ideabuck.inspection;

import com.facebook.buck.intellij.ideabuck.PlatformTestCaseWithBuckCells;
import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckString;
import com.facebook.buck.intellij.ideabuck.visibility.BuckVisibilityState;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import java.nio.file.Path;
import java.util.Optional;

public class BuckDepVisibilityInspectionTest extends PlatformTestCaseWithBuckCells {
  private PsiElement getPsiRoot(Path path) {
    return Optional.of(asVirtualFile(path))
        .map(file -> PsiManager.getInstance(getProject()).findFile(file))
        .map(psiFile -> psiFile.getFirstChild())
        .get();
  }

  public void testGetParentBuckTargetPattern() {
    String content = "fb_java_library(\n" + "name = \"bar\"\n" + ")";
    Path path = createFileInDefaultCell("foo/BUCK", content);
    BuckString buckString = PsiTreeUtil.findChildOfType(getPsiRoot(path), BuckString.class);
    BuckTargetPattern pat =
        BuckDepVisibilityInspection.Visitor.getParentBuckTargetPattern(
            buckTargetLocator, buckString);
    assertEquals("//foo:bar", pat.toString());
  }

  public void testGetBuckTargetPatternFromBuckString() {
    String content = "fb_java_library(\n" + "deps = [\"//abc:def\"]\n" + ")";
    Path path = createFileInDefaultCell("foo/BUCK", content);
    BuckString buckString = PsiTreeUtil.findChildOfType(getPsiRoot(path), BuckString.class);
    BuckTargetPattern pattern =
        BuckDepVisibilityInspection.Visitor.getBuckTargetPatternFromBuckString(
            buckTargetLocator, buckString);
    assertEquals("//abc:def", pattern.toString());
  }

  public void testVisibilityOfTargets() {
    String content =
        "fb_java_library(\n" + "name = \"xyz\",\n" + "visibility = [\"//abc:def\"]\n" + ")";
    Path path = createFileInDefaultCell("foo/BUCK", content);
    BuckTargetPattern pattern = BuckTargetPattern.parse("//foo:xyz").get();
    BuckVisibilityState state =
        BuckDepVisibilityInspection.Visitor.getVisibilityStateWithList(
                project, buckTargetLocator, asVirtualFile(path), pattern)
            .getFirst();
    assertEquals(
        BuckVisibilityState.VisibleState.VISIBLE,
        state.getVisibility(BuckTarget.parse("//abc:def").get()));
    assertEquals(
        BuckVisibilityState.VisibleState.NOT_VISIBLE,
        state.getVisibility(BuckTarget.parse("//abc:ghi").get()));
  }

  public void testPublicVisibility() {
    String content =
        "fb_java_library(\n" + "name = \"xyz\",\n" + "visibility = [\"PUBLIC\"]\n" + ")";
    Path path = createFileInDefaultCell("foo/BUCK", content);
    BuckTargetPattern pattern = BuckTargetPattern.parse("//foo:xyz").get();
    BuckVisibilityState state =
        BuckDepVisibilityInspection.Visitor.getVisibilityStateWithList(
                project, buckTargetLocator, asVirtualFile(path), pattern)
            .getFirst();
    assertEquals(
        BuckVisibilityState.VisibleState.VISIBLE,
        state.getVisibility(BuckTarget.parse("//abc:def").get()));
  }
}
