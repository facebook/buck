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

package com.facebook.buck.intellij.ideabuck.endtoend;

import com.facebook.buck.intellij.ideabuck.file.BuckFileUtil;
import com.facebook.buck.intellij.ideabuck.lang.BuckParserDefinition;
import com.intellij.testFramework.ParsingTestCase;

public class BuckParsingTest extends ParsingTestCase {

  public BuckParsingTest() {
    super("buck", BuckFileUtil.getBuildFileName(), new BuckParserDefinition());
  }

  @Override
  protected String getTestDataPath() {
    return "tests/testdata/psi";
  }

  @Override
  protected boolean skipSpaces() {
    return true;
  }

  private void doTest() {
    doTest(true);
  }

  public void testEmpty() {
    doTest();
  }

  public void testSimple1() {
    doTest();
  }

  public void testSimple2() {
    doTest();
  }

  public void testGlob1() {
    doTest();
  }

  public void testGlobExclude() {
    doTest();
  }

  public void testGlob2() {
    doTest();
  }

  public void testGlob3() {
    doTest();
  }

  public void testInclude() {
    doTest();
  }

  public void testNested() {
    doTest();
  }

  public void testLineComments() {
    doTest();
  }

  public void testLineComments2() {
    doTest();
  }

  public void testLineComments3() {
    doTest();
  }

  public void testLineContinuations() {
    doTest();
  }

  public void testTwoRules1() {
    doTest();
  }

  public void testTwoRules2() {
    doTest();
  }

  public void testStrings() {
    doTest();
  }

  public void testIntegers() {
    doTest();
  }

  public void testAssignment() {
    doTest();
  }

  public void testAugmentedAssignment() {
    doTest();
  }

  public void testComprehensions() {
    doTest();
  }

  public void testSlicing() {
    doTest();
  }

  public void testExpressions() {
    doTest();
  }

  public void testExtensionFile() {
    doTest();
  }

  public void testPutTogether() {
    doTest();
  }
}
