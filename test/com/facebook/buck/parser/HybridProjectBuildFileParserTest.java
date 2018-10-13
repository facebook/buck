/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.Syntax;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.skylark.parser.SkylarkProjectBuildFileParser;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.easymock.EasyMock;
import org.easymock.EasyMockRunner;
import org.easymock.Mock;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@RunWith(EasyMockRunner.class)
public class HybridProjectBuildFileParserTest {

  private static final BuildFileManifest EMPTY_BUILD_FILE_MANIFEST =
      BuildFileManifest.of(
          ImmutableMap.of(),
          ImmutableSortedSet.of(),
          ImmutableMap.of(),
          Optional.empty(),
          ImmutableList.of());

  @Mock PythonDslProjectBuildFileParser pythonDslParser;
  @Mock SkylarkProjectBuildFileParser skylarkParser;

  private HybridProjectBuildFileParser parser;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private Path buildFile;

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws Exception {
    parser =
        HybridProjectBuildFileParser.using(
            ImmutableMap.of(Syntax.PYTHON_DSL, pythonDslParser, Syntax.SKYLARK, skylarkParser),
            Syntax.PYTHON_DSL);
    buildFile = tmp.newFile("BUCK");
  }

  @Test
  public void getAllRulesCallsPythonDslParserWhenRequestedExplicitly() throws Exception {
    EasyMock.expect(pythonDslParser.getBuildFileManifest(buildFile))
        .andReturn(EMPTY_BUILD_FILE_MANIFEST);
    EasyMock.replay(pythonDslParser);
    Files.write(buildFile, getParserDirectiveFor(Syntax.PYTHON_DSL).getBytes());
    parser.getBuildFileManifest(buildFile);
    EasyMock.verify(pythonDslParser);
  }

  @Test
  public void getAllRulesCallsPythonDslParserByDefault() throws Exception {
    EasyMock.expect(pythonDslParser.getBuildFileManifest(buildFile))
        .andReturn(EMPTY_BUILD_FILE_MANIFEST);
    EasyMock.replay(pythonDslParser);
    parser.getBuildFileManifest(buildFile);
    EasyMock.verify(pythonDslParser);
  }

  @Test
  public void getAllRulesCallsSkylarkParserByWhenItIsRequestedExplicitly() throws Exception {
    EasyMock.expect(skylarkParser.getBuildFileManifest(buildFile))
        .andReturn(EMPTY_BUILD_FILE_MANIFEST);
    EasyMock.replay(skylarkParser);
    Files.write(buildFile, getParserDirectiveFor(Syntax.SKYLARK).getBytes());
    parser.getBuildFileManifest(buildFile);
    EasyMock.verify(skylarkParser);
  }

  @Test
  public void getAllRulesCallsFailsOnInvalidSyntaxName() throws Exception {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage(
        "Unrecognized syntax [SKILARK] requested for build file [" + buildFile + "]");
    Files.write(buildFile, "# BUILD FILE SYNTAX: SKILARK".getBytes());
    parser.getBuildFileManifest(buildFile);
  }

  @Test
  public void getBuildFileManifestCallsFailsOnUnsupportedSyntax() throws Exception {
    thrown.expect(BuildFileParseException.class);
    thrown.expectMessage("Syntax [PYTHON_DSL] is not supported for build file [" + buildFile + "]");
    parser =
        HybridProjectBuildFileParser.using(
            ImmutableMap.of(Syntax.SKYLARK, skylarkParser), Syntax.SKYLARK);
    Files.write(buildFile, "# BUILD FILE SYNTAX: PYTHON_DSL".getBytes());
    parser.getBuildFileManifest(buildFile);
  }

  @Test
  public void reportProfileIsCalledForBothParsers() throws Exception {
    pythonDslParser.reportProfile();
    EasyMock.expectLastCall();
    skylarkParser.reportProfile();
    EasyMock.expectLastCall();
    EasyMock.replay(pythonDslParser, skylarkParser);
    parser.reportProfile();
    EasyMock.verify(pythonDslParser, skylarkParser);
  }

  @Test
  public void closeIsCalledForBothParsers() throws Exception {
    pythonDslParser.close();
    EasyMock.expectLastCall();
    skylarkParser.close();
    EasyMock.expectLastCall();
    EasyMock.replay(pythonDslParser, skylarkParser);
    parser.close();
    EasyMock.verify(pythonDslParser, skylarkParser);
  }

  @Test
  public void canInferSyntaxByName() {
    assertThat(Syntax.from("SKYLARK").get(), Matchers.is(Syntax.SKYLARK));
    assertThat(Syntax.from("PYTHON_DSL").get(), Matchers.is(Syntax.PYTHON_DSL));
  }

  @Test
  public void invalidSyntaxIsRecognized() {
    assertFalse(Syntax.from("INVALID_SYNTAX").isPresent());
  }

  /**
   * @return The buck parser directive, which should be added as the first line of the build file in
   *     order to request current syntax.
   */
  private String getParserDirectiveFor(Syntax syntax) {
    return HybridProjectBuildFileParser.SYNTAX_MARKER_START + syntax.name();
  }
}
