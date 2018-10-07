/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.parser;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.rules.visibility.VisibilityPatternParser;
import org.junit.Before;
import org.junit.Test;

public class VisibilityPatternParserTest {

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
  }

  @Test
  public void visibilityParserCanHandleSpecialCasedPublicVisibility()
      throws NoSuchBuildTargetException {
    VisibilityPattern publicPattern = VisibilityPatternParser.parse(null, "PUBLIC");
    assertNotNull(publicPattern);
    assertEquals("PUBLIC", publicPattern.getRepresentation());
  }

  @Test
  public void getDescriptionWorksForVariousPatternTypes() throws NoSuchBuildTargetException {
    assertEquals("PUBLIC", VisibilityPatternParser.parse(null, "PUBLIC").getRepresentation());
    assertEquals(
        "//test/com/facebook/buck/parser:parser",
        parseVisibilityPattern("//test/com/facebook/buck/parser:parser").getRepresentation());
    assertEquals(
        "//test/com/facebook/buck/parser:",
        parseVisibilityPattern("//test/com/facebook/buck/parser:").getRepresentation());
    assertEquals(
        "//test/com/facebook/buck/parser/...",
        parseVisibilityPattern("//test/com/facebook/buck/parser/...").getRepresentation());
  }

  private VisibilityPattern parseVisibilityPattern(String pattern) {
    return VisibilityPatternParser.parse(createCellRoots(filesystem), pattern);
  }
}
