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

package com.facebook.buck.rules.macros;

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class BuildTargetMacroExpanderTest {

  private static Optional<BuildTarget> match(String blob) throws MacroException {
    final List<BuildTarget> found = new ArrayList<>();
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver sourcePathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(resolver));
    FakeBuildRule rule = new FakeBuildRule("//something:manifest", sourcePathResolver);
    resolver.addToIndex(rule);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildTargetMacroExpander<?> macroExpander =
        new ExecutableMacroExpander() {
          @Override
          public String expand(SourcePathResolver resolver, BuildRule rule) throws MacroException {
            found.add(rule.getBuildTarget());
            return "";
          }
        };
    MacroHandler handler = new MacroHandler(ImmutableMap.of("exe", macroExpander));
    handler.expand(rule.getBuildTarget(), createCellRoots(filesystem), resolver, blob);
    return Optional.ofNullable(Iterables.getFirst(found, null));
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  private void assertThrows(String blob) {
    try {
      match(blob);
      fail("expected to throw");
    } catch (MacroException e) {
    }
  }

  @Test
  public void buildTargetPattern() throws MacroException {
    assertTrue(match("$(exe //something:manifest)").isPresent());
    assertFalse(match("\\$(exe //something:manifest)").isPresent());
    assertThrows("$(exe something:manifest)");
    assertTrue(match("$(exe :manifest)").isPresent());
    assertThrows("$(exe //:manifest)");
    assertTrue(match("SOMETHING=something $(exe :manifest) something $OUT").isPresent());
  }

  @Test
  public void extractTargets() throws MacroException {
    Optional<BuildTarget> target = match("$(exe //something:manifest)");
    assertEquals(Optional.of(BuildTargetFactory.newInstance("//something:manifest")), target);
  }
}
