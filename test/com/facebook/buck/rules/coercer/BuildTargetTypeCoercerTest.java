/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystem;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class BuildTargetTypeCoercerTest {

  private BuildTargetParser targetParser = new BuildTargetParser();
  private BuildRuleResolver resolver = new BuildRuleResolver();
  private ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private Path basePath = Paths.get("java/com/facebook/buck/example");

  @Test
  public void canCoerceAnUnflavoredFullyQualifiedTarget() throws CoerceFailedException {
    BuildTarget seen = new BuildTargetTypeCoercer().coerce(
        targetParser,
        resolver,
        filesystem,
        basePath,
        "//foo:bar");

    assertEquals(BuildTarget.builder("//foo", "bar").build(), seen);
  }

  @Test
  public void shouldCoerceAShortTarget() throws CoerceFailedException {
    BuildTarget seen = new BuildTargetTypeCoercer().coerce(
        targetParser,
        resolver,
        filesystem,
        basePath,
        ":bar");

    assertEquals(BuildTarget.builder("//java/com/facebook/buck/example", "bar").build(), seen);
  }

  @Test
  public void shouldCoerceATargetWithASingleFlavor() throws CoerceFailedException {
    BuildTarget seen = new BuildTargetTypeCoercer().coerce(
        targetParser,
        resolver,
        filesystem,
        basePath,
        "//foo:bar#baz");

    assertEquals(BuildTarget.builder("//foo", "bar").addFlavor("baz").build(), seen);
  }

  @Test
  public void shouldCoerceMultipleFlavors() throws CoerceFailedException {
    BuildTarget seen = new BuildTargetTypeCoercer().coerce(
        targetParser,
        resolver,
        filesystem,
        basePath,
        "//foo:bar#baz,qux");

    assertEquals(
        BuildTarget.builder("//foo", "bar").addFlavor("baz").addFlavor("qux").build(),
        seen);
  }

  @Test
  public void shouldCoerceAShortTargetWithASingleFlavor() throws CoerceFailedException {
    BuildTarget seen = new BuildTargetTypeCoercer().coerce(
        targetParser,
        resolver,
        filesystem,
        basePath,
        ":bar#baz");

    BuildTarget expected = BuildTarget.builder("//java/com/facebook/buck/example", "bar")
        .addFlavor("baz")
        .build();
    assertEquals(expected, seen);
  }
}
