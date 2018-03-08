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

package com.facebook.buck.cxx;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.oneOf;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.Ansi;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Tests that various error line path replacements happen (or doesn't happen) in both relative and
 * absolute path modes.
 */
@RunWith(Parameterized.class)
public class CxxErrorTransformerTest {

  @Parameterized.Parameters(name = "{index}: {0}")
  public static Collection<Object[]> data() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver ruleResolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));

    Path original = filesystem.resolve("buck-out/foo#bar/world.h");
    Path replacement = filesystem.resolve("hello/world.h");

    HeaderPathNormalizer.Builder normalizerBuilder = new HeaderPathNormalizer.Builder(pathResolver);
    normalizerBuilder.addHeader(FakeSourcePath.of(replacement.toString()), original);
    HeaderPathNormalizer normalizer = normalizerBuilder.build();

    return ImmutableList.copyOf(
        new Object[][] {
          {
            "relative paths",
            new CxxErrorTransformer(filesystem, false, normalizer),
            filesystem.relativize(replacement),
            original
          },
          {
            "absolute paths",
            new CxxErrorTransformer(filesystem, true, normalizer),
            replacement.toAbsolutePath(),
            original
          }
        });
  }

  @Parameterized.Parameter(value = 0)
  public String datasetName;

  @Parameterized.Parameter(value = 1)
  public CxxErrorTransformer transformer;

  @Parameterized.Parameter(value = 2)
  public Path expectedPath;

  @Parameterized.Parameter(value = 3)
  public Path originalPath;

  @Test
  public void shouldProperlyTransformErrorLinesInPrimaryIncludeTrace() {
    assertThat(
        transformer.transformLine(String.format("In file included from %s:", originalPath)),
        equalTo(String.format("In file included from %s:", expectedPath)));
    assertThat(
        transformer.transformLine(String.format("In file included from %s:3:2:", originalPath)),
        equalTo(String.format("In file included from %s:3:2:", expectedPath)));
    assertThat(
        transformer.transformLine(String.format("In file included from %s,", originalPath)),
        equalTo(String.format("In file included from %s,", expectedPath)));
    assertThat(
        transformer.transformLine(String.format("In file included from %s:7,", originalPath)),
        equalTo(String.format("In file included from %s:7,", expectedPath)));
  }

  @Test
  public void shouldProperlyTransformLinesInSubsequentIncludeTrace() {
    assertThat(
        transformer.transformLine(String.format("   from %s:", originalPath)),
        equalTo(String.format("   from %s:", expectedPath)));
    assertThat(
        transformer.transformLine(String.format("   from %s:3:2:", originalPath)),
        equalTo(String.format("   from %s:3:2:", expectedPath)));
    assertThat(
        transformer.transformLine(String.format("   from %s,", originalPath)),
        equalTo(String.format("   from %s,", expectedPath)));
    assertThat(
        transformer.transformLine(String.format("   from %s:7,", originalPath)),
        equalTo(String.format("   from %s:7,", expectedPath)));
  }

  @Test
  public void shouldProperlyTransformLinesInErrorMessages() {
    assertThat(
        transformer.transformLine(String.format("%s: something bad", originalPath)),
        equalTo(String.format("%s: something bad", expectedPath)));
    assertThat(
        transformer.transformLine(String.format("%s:4: something bad", originalPath)),
        equalTo(String.format("%s:4: something bad", expectedPath)));
    assertThat(
        transformer.transformLine(String.format("%s:4:2: something bad", originalPath)),
        equalTo(String.format("%s:4:2: something bad", expectedPath)));
  }

  @Test
  public void shouldProperlyTransformColoredLinesInErrorMessages() {
    Ansi ansi = new Ansi(/* isAnsiTerminal */ true);
    assertThat(
        transformer.transformLine(
            String.format("%s something bad", ansi.asErrorText(originalPath + ":"))),
        equalTo(String.format("%s something bad", ansi.asErrorText(expectedPath + ":"))));
    assertThat(
        transformer.transformLine(
            String.format("%s something bad", ansi.asErrorText(originalPath + ":4:"))),
        equalTo(String.format("%s something bad", ansi.asErrorText(expectedPath + ":4:"))));
    assertThat(
        transformer.transformLine(
            String.format("%s something bad", ansi.asErrorText(originalPath + ":4:2:"))),
        equalTo(String.format("%s something bad", ansi.asErrorText(expectedPath + ":4:2:"))));
    assertThat(
        transformer.transformLine(
            String.format(
                "In file included from \u001b[01m\u001b[K%s:7\u001b[m\u001b[K,", originalPath)),
        equalTo(
            String.format(
                "In file included from \u001b[01m\u001b[K%s:7\u001b[m\u001b[K,", expectedPath)));
    assertThat(
        transformer.transformLine(
            String.format("    from \u001b[01m\u001b[K%s:7\u001b[m\u001b[K,", originalPath)),
        equalTo(String.format("    from \u001b[01m\u001b[K%s:7\u001b[m\u001b[K,", expectedPath)));
    assertThat(
        transformer.transformLine(
            String.format("\u001b[01m\u001b[K%s:7\u001b[m\u001b[K,", originalPath)),
        equalTo(String.format("\u001b[01m\u001b[K%s:7\u001b[m\u001b[K,", expectedPath)));
    assertThat(
        transformer.transformLine(String.format("\u001b[01m%s:7\u001b[m,", originalPath)),
        equalTo(String.format("\u001b[01m%s:7\u001b[m,", expectedPath)));
  }

  @Test
  public void shouldNotTransformLineNotInReplacementMap() {
    assertThat(
        transformer.transformLine("In file included from test.h:"),
        oneOf(
            // relative/absolute should still resolve, but otherwise the path should be unchanged.
            "In file included from test.h:",
            String.format(
                "In file included from %s:", Paths.get("test.h").toAbsolutePath().toString())));
  }

  @Test
  public void shouldNotTransformLineWithoutLocations() {
    assertThat(transformer.transformLine(" error message!"), equalTo(" error message!"));
  }
}
