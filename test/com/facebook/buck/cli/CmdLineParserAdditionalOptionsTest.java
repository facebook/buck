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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.IllegalAnnotationError;
import org.kohsuke.args4j.Option;

public class CmdLineParserAdditionalOptionsTest {

  public static final String O1 = "--one", V1 = "1";
  public static final String O2 = "--two", V2 = "2";
  public static final String O3 = "--three", V3 = "3";
  public static final String O4 = "--four", V4 = "4";

  private RootOptions options;
  private CmdLineParser parser;

  static class RootOptions {
    @Option(name = O1)
    String one;

    @AdditionalOptions
    SubOptions sub;

    @AdditionalOptions
    SubOptions2 sub2;

    static class SubOptions {
      @Option(name = O2)
      String two;

      @AdditionalOptions
      SubSubOptions sub;
    }
  }

  static class SubOptions2 {
    @Option(name = O3)
    String three;
  }

  static class SubSubOptions {
    @Option(name = O4)
    String four;
  }

  static class InfiniteOptions {
    @AdditionalOptions
    InfiniteOptions infiniteOptions;
  }

  static class DuplicateOptions {
    @AdditionalOptions
    Object object1; // There are no args4j annotations in Object, so we can use that.
    @AdditionalOptions
    Object object2;
  }

  @Before
  public void setUp() {
     options = new RootOptions();
     parser = new CmdLineParserAdditionalOptions(options);
  }

  @Test(expected = IllegalAnnotationError.class)
  public void testDuplicateAdditionalOptionsClass() {
      new CmdLineParserAdditionalOptions(new DuplicateOptions());
  }

  @Test(expected = IllegalAnnotationError.class)
  public void testRecursiveAdditionalOptions() {
      new CmdLineParserAdditionalOptions(new InfiniteOptions());
  }

  @Test
  public void testAllOptions() throws CmdLineException {
    parser.parseArgument(O1, V1, O2, V2, O3, V3, O4, V4);
    assertEquals(V1, options.one);
    assertEquals(V2, options.sub.two);
    assertEquals(V3, options.sub2.three);
    assertEquals(V4, options.sub.sub.four);
  }

  @Test
  public void testAdditionalOptionsInstantiation() {
    // Currently this happens at parser creation time, might change in the future.
    assertNotNull(options.sub);
    assertNotNull(options.sub2);
    assertNotNull(options.sub.sub);
  }

  @Test
  public void testNoOptions() throws CmdLineException {
    parser.parseArgument();

    assertNull(options.one);
    assertNull(options.sub.two);
    assertNull(options.sub2.three);
    assertNull(options.sub.sub.four);
  }
}
