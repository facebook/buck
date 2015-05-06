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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildFileSpec;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternTargetNodeParser;
import com.facebook.buck.parser.BuildTargetSpec;
import com.facebook.buck.parser.TargetNodePredicateSpec;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CommandLineTargetNodeSpecParserTest {

  private static final CommandLineTargetNodeSpecParser PARSER =
      new CommandLineTargetNodeSpecParser(
          new FakeBuckConfig(),
          new BuildTargetPatternTargetNodeParser(new BuildTargetParser(), ImmutableSet.<Path>of()));

  @Test
  public void trailingDotDotDot() {
    assertEquals(
        TargetNodePredicateSpec.of(
            Predicates.alwaysTrue(),
            BuildFileSpec.fromRecursivePath(Paths.get("hello"))),
        PARSER.parse("//hello/..."));
    assertEquals(
        TargetNodePredicateSpec.of(
            Predicates.alwaysTrue(),
            BuildFileSpec.fromRecursivePath(Paths.get(""))),
        PARSER.parse("//..."));
    assertEquals(
        TargetNodePredicateSpec.of(
            Predicates.alwaysTrue(),
            BuildFileSpec.fromRecursivePath(Paths.get(""))),
        PARSER.parse("..."));
    assertEquals(
        BuildTargetSpec.from(BuildTargetFactory.newInstance("//hello:...")),
        PARSER.parse("//hello:..."));
  }

  @Test
  public void tailingColon() {
    assertEquals(
        TargetNodePredicateSpec.of(
            Predicates.alwaysTrue(),
            BuildFileSpec.fromPath(Paths.get("hello"))),
        PARSER.parse("//hello:"));
  }

  @Test
  public void normalizeBuildTargets() {
    assertEquals("//:", PARSER.normalizeBuildTargetString("//:"));
    assertEquals("//:", PARSER.normalizeBuildTargetString(":"));
    assertEquals("//...", PARSER.normalizeBuildTargetString("//..."));
    assertEquals("//...", PARSER.normalizeBuildTargetString("..."));
  }

}
