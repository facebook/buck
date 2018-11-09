/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.intellij.ideabuck.actions.select;

import static org.junit.Assert.*;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase;
import java.util.List;
import java.util.function.Predicate;

public class SelectedTestRunLineMarkerContributorTest extends LightCodeInsightFixtureTestCase {

  private static String join(String... lines) {
    StringBuilder sb = new StringBuilder();
    for (String line : lines) {
      sb.append(line).append("\n");
    }
    return sb.toString();
  }

  private AnAction findActionAtCaretWithText(Predicate<String> textMatcher) {
    List<GutterMark> gutterMarks = myFixture.findGuttersAtCaret();
    for (GutterMark gutterMark : gutterMarks) {
      if (!(gutterMark instanceof GutterIconRenderer)) {
        continue;
      }
      GutterIconRenderer renderer = (GutterIconRenderer) gutterMark;
      ActionGroup group = renderer.getPopupMenuActions();
      for (AnAction action : group.getChildren(new TestActionEvent())) {
        TestActionEvent actionEvent = new TestActionEvent();
        action.update(actionEvent);
        String actualText = actionEvent.getPresentation().getText();
        if (actualText == null) {
          actualText = action.getTemplatePresentation().getText();
          if (actualText == null) {
            continue;
          }
        }
        if (textMatcher.test(actualText)) {
          return action;
        }
      }
    }
    return null;
  }

  public void testNonTestClassIsNotAnnotated() {
    myFixture.addClass("package junit.framework; public class TestCase {}");
    myFixture.configureByText(
        "ClassName.java",
        join("public class <caret>ClassName {", "    public void methodName() {}", "}"));
    assertNull(findActionAtCaretWithText(s -> s.contains("ClassName")));
  }

  public void testNonTestMethodIsNotAnnotated() {
    myFixture.addClass("package junit.framework; public class TestCase {}");
    myFixture.configureByText(
        "ClassName.java",
        join(
            "public class ClassName extends junit.framework.TestCase {",
            "    public void <caret>methodName() {}",
            "}"));
    assertNull(findActionAtCaretWithText(s -> s.contains("methodName")));
  }

  public void testRunClassMarkerAppearsOnSameLineAsClassNameIdentifier() {
    myFixture.addClass("package junit.framework; public class TestCase {}");
    myFixture.configureByText(
        "ClassName.java",
        join(
            "public",
            "class",
            "<caret>ClassName",
            "extends",
            "junit.framework.TestCase",
            "{",
            "    public void testSomething() {}",
            "}"));
    assertNotNull(findActionAtCaretWithText(s -> s.equals("Run 'ClassName' with Buck")));
    assertNotNull(findActionAtCaretWithText(s -> s.equals("Debug 'ClassName' with Buck")));
  }

  public void testRunMethodMarkerAppearsOnSameLineAsMethodNameIdentifier() {
    myFixture.addClass("package org.junit; public @interface Test {}");
    myFixture.configureByText(
        "ClassName.java",
        join(
            "public class ClassName {",
            "    @org.junit.Test",
            "    public void",
            "    <caret>methodName",
            "    ()",
            "    {",
            "    }",
            "}"));
    assertNotNull(findActionAtCaretWithText(s -> s.equals("Run 'methodName()' with Buck")));
    assertNotNull(findActionAtCaretWithText(s -> s.equals("Debug 'methodName()' with Buck")));
  }
}
