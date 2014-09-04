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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.model.ImmediateDirectoryBuildTargetPattern;
import com.facebook.buck.model.SingletonBuildTargetPattern;
import com.facebook.buck.model.SubdirectoryBuildTargetPattern;

import org.junit.Test;

public class BuildTargetPatternParserTest {

  @Test
  public void testParse() throws NoSuchBuildTargetException {
    BuildTargetPatternParser parser = new BuildTargetPatternParser();
    ParseContext parseContext = ParseContext.forVisibilityArgument();

    assertEquals(
        new ImmediateDirectoryBuildTargetPattern("test/com/facebook/buck/parser/"),
        parser.parse("//test/com/facebook/buck/parser:", parseContext));

    assertEquals(
        new SingletonBuildTargetPattern("//test/com/facebook/buck/parser:parser"),
        parser.parse("//test/com/facebook/buck/parser:parser", parseContext));

    assertEquals(
        new SubdirectoryBuildTargetPattern("test/com/facebook/buck/parser/"),
        parser.parse("//test/com/facebook/buck/parser/...", parseContext));
  }

  @Test(expected = BuildTargetParseException.class)
  public void testParseWildcardWithInvalidContext() throws NoSuchBuildTargetException {
    BuildTargetPatternParser parser = new BuildTargetPatternParser();
    ParseContext parseContext = ParseContext.fullyQualified();

    parser.parse("//...", parseContext);
    fail();
  }

  @Test
  public void testParseRootPattern() throws NoSuchBuildTargetException {
    BuildTargetPatternParser parser = new BuildTargetPatternParser();
    ParseContext parseContext = ParseContext.forVisibilityArgument();

    assertEquals(
        new ImmediateDirectoryBuildTargetPattern(""),
        parser.parse("//:", parseContext));

    assertEquals(
        new SingletonBuildTargetPattern("//:parser"),
        parser.parse("//:parser", parseContext));

    assertEquals(
        new SubdirectoryBuildTargetPattern(""),
        parser.parse("//...", parseContext));
  }
}
