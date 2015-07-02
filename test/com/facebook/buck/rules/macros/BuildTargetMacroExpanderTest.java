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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.junit.Test;

import java.util.List;

public class BuildTargetMacroExpanderTest {

  private static Optional<BuildTarget> match(String blob) throws MacroException {
    final List<BuildTarget> found = Lists.newArrayList();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    FakeBuildRule rule = new FakeBuildRule("//something:manifest", sourcePathResolver);
    resolver.addToIndex(rule);
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    MacroHandler handler = new MacroHandler(
        ImmutableMap.<String, MacroExpander>of(
            "exe",
            new BuildTargetMacroExpander() {
              @Override
              public String expand(ProjectFilesystem filesystem, BuildRule rule)
                  throws MacroException {
                found.add(rule.getBuildTarget());
                return "";
              }
            }));
    handler.expand(rule.getBuildTarget(), resolver, filesystem, blob);
    return Optional.fromNullable(Iterables.getFirst(found, null));
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  private void assertThrows(String blob) {
    try {
      match(blob);
      fail("expected to throw");
    } catch (MacroException e) {}
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
    assertEquals(
        Optional.of(
            BuildTargetFactory.newInstance("//something:manifest")),
        target);
  }

}
