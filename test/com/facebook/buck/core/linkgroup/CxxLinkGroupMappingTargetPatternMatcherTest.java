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

package com.facebook.buck.core.linkgroup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcher;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcherParser;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import org.junit.Test;

public class CxxLinkGroupMappingTargetPatternMatcherTest {

  private final FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final CellNameResolver cellRoots =
      TestCellPathResolver.get(filesystem).getCellNameResolver();

  private static final BuildTargetMatcherParser<BuildTargetMatcher> buildTargetPatternParser =
      BuildTargetMatcherParser.forVisibilityArgument();

  @Test
  public void matchesValidResursiveTargetPattern() {
    String pattern = "//foo/...";
    CxxLinkGroupMappingTargetPatternMatcher testMatcher =
        new CxxLinkGroupMappingTargetPatternMatcher(
            pattern, buildTargetPatternParser.parse(pattern, cellRoots));

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?> fooLibTargetNode = new CxxLibraryBuilder(fooLibTarget).build();
    assertTrue(testMatcher.matchesNode(fooLibTargetNode));
  }

  @Test
  public void matchesValidPackageTargetPattern() {
    String pattern = "//foo:";
    CxxLinkGroupMappingTargetPatternMatcher testMatcher =
        new CxxLinkGroupMappingTargetPatternMatcher(
            pattern, buildTargetPatternParser.parse(pattern, cellRoots));

    BuildTarget fooLibTarget = BuildTargetFactory.newInstance("//foo:lib");
    TargetNode<?> fooLibTargetNode = new CxxLibraryBuilder(fooLibTarget).build();
    assertTrue(testMatcher.matchesNode(fooLibTargetNode));
  }

  @Test
  public void doesNotMatchInvalidTarget() {
    String pattern = "//foo/...";
    CxxLinkGroupMappingTargetPatternMatcher testMatcher =
        new CxxLinkGroupMappingTargetPatternMatcher(
            pattern, buildTargetPatternParser.parse(pattern, cellRoots));

    BuildTarget barLibTarget = BuildTargetFactory.newInstance("//bar:lib");
    TargetNode<?> barLibTargetNode = new CxxLibraryBuilder(barLibTarget).build();
    assertFalse(testMatcher.matchesNode(barLibTargetNode));
  }
}
