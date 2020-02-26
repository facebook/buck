/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.parser.buildtargetparser;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.CellPathResolverView;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.TestCellNameResolver;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.parser.spec.BuildTargetMatcherTargetNodeParser;
import com.facebook.buck.parser.spec.BuildTargetSpec;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.stream.Stream;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuildTargetMatcherParserTest {

  private ProjectFilesystem filesystem;

  @Rule public ExpectedException exception = ExpectedException.none();

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem();
  }

  @Test
  public void testParse() throws NoSuchBuildTargetException {
    BuildTargetMatcherParser<BuildTargetMatcher> buildTargetPatternParser =
        BuildTargetMatcherParser.forVisibilityArgument();

    assertEquals(
        ImmutableImmediateDirectoryBuildTargetMatcher.of(
            CellRelativePath.of(
                CanonicalCellName.rootCell(),
                ForwardRelativePath.of("test/com/facebook/buck/parser"))),
        buildTargetPatternParser.parse(
            "//test/com/facebook/buck/parser:", createCellRoots(filesystem).getCellNameResolver()));

    assertEquals(
        ImmutableSingletonBuildTargetMatcher.of(
            BuildTargetFactory.newInstance("//test/com/facebook/buck/parser:parser")
                .getUnconfiguredBuildTarget()),
        buildTargetPatternParser.parse(
            "//test/com/facebook/buck/parser:parser",
            createCellRoots(filesystem).getCellNameResolver()));

    assertEquals(
        ImmutableSubdirectoryBuildTargetMatcher.of(
            CellRelativePath.of(
                CanonicalCellName.unsafeRootCell(),
                ForwardRelativePath.of("test/com/facebook/buck/parser"))),
        buildTargetPatternParser.parse(
            "//test/com/facebook/buck/parser/...",
            createCellRoots(filesystem).getCellNameResolver()));
  }

  @Test
  public void testParseRootPattern() throws NoSuchBuildTargetException {
    BuildTargetMatcherParser<BuildTargetMatcher> buildTargetPatternParser =
        BuildTargetMatcherParser.forVisibilityArgument();

    assertEquals(
        ImmutableImmediateDirectoryBuildTargetMatcher.of(
            CellRelativePath.of(CanonicalCellName.rootCell(), ForwardRelativePath.of(""))),
        buildTargetPatternParser.parse("//:", createCellRoots(filesystem).getCellNameResolver()));

    assertEquals(
        ImmutableSingletonBuildTargetMatcher.of(
            BuildTargetFactory.newInstance("//:parser").getUnconfiguredBuildTarget()),
        buildTargetPatternParser.parse(
            "//:parser", createCellRoots(filesystem).getCellNameResolver()));

    assertEquals(
        ImmutableSubdirectoryBuildTargetMatcher.of(
            CellRelativePath.of(CanonicalCellName.unsafeRootCell(), ForwardRelativePath.of(""))),
        buildTargetPatternParser.parse("//...", createCellRoots(filesystem).getCellNameResolver()));
  }

  @Test
  public void visibilityCanContainCrossCellReference() {
    BuildTargetMatcherParser<BuildTargetMatcher> buildTargetPatternParser =
        BuildTargetMatcherParser.forVisibilityArgument();

    CellPathResolver cellNames =
        TestCellPathResolver.create(
            filesystem.resolve("foo/root"),
            ImmutableMap.of("other", filesystem.getPath("../other")));

    assertEquals(
        ImmutableSingletonBuildTargetMatcher.of(
            BuildTargetFactory.newInstance("other//:something").getUnconfiguredBuildTarget()),
        buildTargetPatternParser.parse("other//:something", cellNames.getCellNameResolver()));
    assertEquals(
        ImmutableSubdirectoryBuildTargetMatcher.of(
            CellRelativePath.of(
                CanonicalCellName.unsafeOf(Optional.of("other")), ForwardRelativePath.of("sub"))),
        buildTargetPatternParser.parse("other//sub/...", cellNames.getCellNameResolver()));
  }

  @Test
  public void visibilityCanMatchCrossCellTargets() {
    BuildTargetMatcherParser<BuildTargetMatcher> buildTargetPatternParser =
        BuildTargetMatcherParser.forVisibilityArgument();

    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    CellPathResolver rootCellPathResolver =
        TestCellPathResolver.create(
            filesystem.resolve("root").normalize(),
            ImmutableMap.of(
                "other", filesystem.getPath("../other").normalize(),
                "root", filesystem.getPath("../root").normalize()));
    CellPathResolver otherCellPathResolver =
        new CellPathResolverView(
            rootCellPathResolver,
            TestCellNameResolver.forSecondary("other", Optional.of("root")),
            ImmutableSet.of("root"),
            filesystem.resolve("other").normalize());
    UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory =
        new ParsingUnconfiguredBuildTargetViewFactory();

    // Root cell visibility from non-root cell
    Stream.of("other//lib:lib", "other//lib:", "other//lib/...")
        .forEach(
            patternString -> {
              BuildTargetMatcher pattern =
                  buildTargetPatternParser.parse(
                      patternString, rootCellPathResolver.getCellNameResolver());
              assertTrue(
                  "from root matching something in non-root: " + pattern,
                  pattern.matches(
                      unconfiguredBuildTargetFactory.create(
                          "//lib:lib", otherCellPathResolver.getCellNameResolver())));
              assertFalse(
                  "from root failing to match something in root: " + pattern,
                  pattern.matches(
                      unconfiguredBuildTargetFactory.create(
                          "//lib:lib", rootCellPathResolver.getCellNameResolver())));
            });

    // Non-root cell visibility from root cell.
    Stream.of("root//lib:lib", "root//lib:", "root//lib/...")
        .forEach(
            patternString -> {
              BuildTargetMatcher pattern =
                  buildTargetPatternParser.parse(
                      patternString, otherCellPathResolver.getCellNameResolver());
              assertTrue(
                  "from non-root matching something in root: " + pattern,
                  pattern.matches(
                      unconfiguredBuildTargetFactory.create(
                          "//lib:lib", rootCellPathResolver.getCellNameResolver())));
              assertFalse(
                  "from non-root matching something in non-root: " + pattern,
                  pattern.matches(
                      unconfiguredBuildTargetFactory.create(
                          "//lib:lib", otherCellPathResolver.getCellNameResolver())));
            });
  }

  @Test
  public void testParseAbsolutePath() {
    // Exception should be thrown by BuildTargetParser.checkBaseName()
    BuildTargetMatcherParser<BuildTargetMatcher> buildTargetPatternParser =
        BuildTargetMatcherParser.forVisibilityArgument();

    exception.expect(BuildTargetParseException.class);
    exception.expectMessage("absolute");
    exception.expectMessage("(found ///facebookorca/...)");
    buildTargetPatternParser.parse(
        "///facebookorca/...", createCellRoots(filesystem).getCellNameResolver());
  }

  @Test
  public void testIncludesTargetNameInMissingCellErrorMessage() {
    BuildTargetMatcherParser<BuildTargetMatcher> buildTargetPatternParser =
        BuildTargetMatcherParser.forVisibilityArgument();

    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    CellPathResolver rootCellPathResolver =
        TestCellPathResolver.create(
            filesystem.resolve("root").normalize(),
            ImmutableMap.of("localreponame", filesystem.resolve("localrepo").normalize()));

    exception.expect(BuildTargetParseException.class);
    // It contains the pattern
    exception.expectMessage("lclreponame//facebook/...");
    // The invalid cell
    exception.expectMessage("Unknown cell: lclreponame");
    // And the suggestion
    exception.expectMessage("localreponame");
    buildTargetPatternParser.parse(
        "lclreponame//facebook/...", rootCellPathResolver.getCellNameResolver());
  }

  @Test
  public void parsesOutputLabel() {
    BuildTargetMatcherParser<TargetNodeSpec> buildTargetPatternParser =
        new BuildTargetMatcherTargetNodeParser();
    UnconfiguredBuildTarget unconfiguredBuildTargetView =
        UnconfiguredBuildTargetFactoryForTests.newInstance(
            filesystem, "//test/com/facebook/buck/parser:parser");

    assertEquals(
        BuildTargetSpec.from(
            UnconfiguredBuildTargetWithOutputs.of(
                unconfiguredBuildTargetView, OutputLabel.of("label"))),
        buildTargetPatternParser.parse(
            "//test/com/facebook/buck/parser:parser[label]",
            createCellRoots(filesystem).getCellNameResolver()));

    assertEquals(
        BuildTargetSpec.from(
            UnconfiguredBuildTargetWithOutputs.of(
                unconfiguredBuildTargetView, OutputLabel.defaultLabel())),
        buildTargetPatternParser.parse(
            "//test/com/facebook/buck/parser:parser",
            createCellRoots(filesystem).getCellNameResolver()));
  }

  @Test
  public void outputLabelCannotBeEmpty() {
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("Output label cannot be empty");

    new BuildTargetMatcherTargetNodeParser()
        .parse(
            "//test/com/facebook/buck/parser:parser[]",
            createCellRoots(filesystem).getCellNameResolver());
  }

  @Test
  public void descendantSyntaxCannotHaveOutputLabel() {
    exception.expect(Matchers.instanceOf(BuildTargetParseException.class));
    exception.expectMessage("//test/com/facebook/buck/parser: should not have output label noms");

    new BuildTargetMatcherTargetNodeParser()
        .parse(
            "//test/com/facebook/buck/parser:[noms]",
            createCellRoots(filesystem).getCellNameResolver());
  }

  @Test
  public void wildcardSyntaxCannotHaveOutputLabel() {
    exception.expect(Matchers.instanceOf(BuildTargetParseException.class));
    exception.expectMessage(
        "//test/com/facebook/buck/parser/... should not have output label noms");

    new BuildTargetMatcherTargetNodeParser()
        .parse(
            "//test/com/facebook/buck/parser/...[noms]",
            createCellRoots(filesystem).getCellNameResolver());
  }

  @Test
  public void doesNotAllowFlavorsWithPackage() {
    exception.expect(Matchers.instanceOf(BuildTargetParseException.class));
    exception.expectMessage("cannot specify flavors for package matcher");

    new BuildTargetMatcherTargetNodeParser()
        .parse("//test:#fla", createCellRoots(filesystem).getCellNameResolver());
  }
}
