/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.parser.buildtargetparser;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetPatternData.Kind;
import java.nio.file.Paths;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class BuildTargetPatternDataParserTest {

  @Rule public ExpectedException exception = ExpectedException.none();

  @SuppressWarnings("unused")
  private Object[] dataParseSuccess() {
    return new Object[] {
      new Object[] {
        "cell//path/to:target",
        ImmutableBuildTargetPatternData.of("cell", Kind.SINGLE, Paths.get("path/to"), "target")
      },
      new Object[] {
        "//path/to:target",
        ImmutableBuildTargetPatternData.of("", Kind.SINGLE, Paths.get("path/to"), "target")
      },
      new Object[] {
        "//path/to", ImmutableBuildTargetPatternData.of("", Kind.SINGLE, Paths.get("path/to"), "to")
      },
      new Object[] {
        "cell//path/to",
        ImmutableBuildTargetPatternData.of("cell", Kind.SINGLE, Paths.get("path/to"), "to")
      },
      new Object[] {
        "//root", ImmutableBuildTargetPatternData.of("", Kind.SINGLE, Paths.get("root"), "root")
      },
      new Object[] {
        "//:target", ImmutableBuildTargetPatternData.of("", Kind.SINGLE, Paths.get(""), "target")
      },
      new Object[] {
        "cell//path/to/...",
        ImmutableBuildTargetPatternData.of("cell", Kind.RECURSIVE, Paths.get("path/to"), "")
      },
      new Object[] {
        "//path/to/...",
        ImmutableBuildTargetPatternData.of("", Kind.RECURSIVE, Paths.get("path/to"), "")
      },
      new Object[] {
        "//...", ImmutableBuildTargetPatternData.of("", Kind.RECURSIVE, Paths.get(""), "")
      },
      new Object[] {
        "cell//...", ImmutableBuildTargetPatternData.of("cell", Kind.RECURSIVE, Paths.get(""), "")
      },
      new Object[] {
        "//path/to:", ImmutableBuildTargetPatternData.of("", Kind.PACKAGE, Paths.get("path/to"), "")
      },
      new Object[] {
        "cell//path/to:",
        ImmutableBuildTargetPatternData.of("cell", Kind.PACKAGE, Paths.get("path/to"), "")
      },
      new Object[] {"//:", ImmutableBuildTargetPatternData.of("", Kind.PACKAGE, Paths.get(""), "")},
      new Object[] {
        "a//b:c", ImmutableBuildTargetPatternData.of("a", Kind.SINGLE, Paths.get("b"), "c")
      },
      new Object[] {
        "a//b", ImmutableBuildTargetPatternData.of("a", Kind.SINGLE, Paths.get("b"), "b")
      },
      new Object[] {
        "//a", ImmutableBuildTargetPatternData.of("", Kind.SINGLE, Paths.get("a"), "a")
      },
    };
  }

  @Test
  @Parameters(method = "dataParseSuccess")
  @TestCaseName("parsingSucceeds({0})")
  public void parsingSucceeds(String pattern, BuildTargetPatternData expected)
      throws BuildTargetParseException {
    assertEquals(expected, BuildTargetPatternDataParser.parse(pattern));
  }

  @Test
  @Parameters({
    "",
    "path/to:target",
    "//",
    "///",
    "...",
    ":",
    "/",
    "/:",
    "/...",
    "///some/path:target",
    "//path/to...",
    "//path/:...",
    "//a/b//c/d:f",
    "//a/b/"
  })
  @TestCaseName("parsingFails({0})")
  public void parsingFails(String pattern) throws BuildTargetParseException {
    exception.expect(BuildTargetParseException.class);
    BuildTargetPatternDataParser.parse(pattern);
  }
}
