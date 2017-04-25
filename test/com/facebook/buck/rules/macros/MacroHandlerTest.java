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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MacroHandlerTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private ProjectFilesystem filesystem;
  private BuildTarget target;
  private BuildRuleResolver resolver;

  @Before
  public void before() throws Exception {
    filesystem = new ProjectFilesystem(tmp.newFolder().toPath());
    target = BuildTargetFactory.newInstance("//:test");
    JavaLibraryBuilder builder = JavaLibraryBuilder.createBuilder(target);
    resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    builder.build(resolver, filesystem);
  }

  @Test
  public void noSuchMacro() {
    MacroHandler handler = new MacroHandler(ImmutableMap.of());
    try {
      handler.expand(target, createCellRoots(filesystem), resolver, "$(badmacro hello)");
    } catch (MacroException e) {
      assertTrue(e.getMessage().contains("no such macro \"badmacro\""));
    }
    try {
      handler.extractParseTimeDeps(
          target,
          createCellRoots(filesystem),
          "$(badmacro hello)",
          new ImmutableSet.Builder<>(),
          new ImmutableSet.Builder<>());
    } catch (MacroException e) {
      assertTrue(e.getMessage().contains("no such macro \"badmacro\""));
    }
  }

  @Test
  public void escapeMacro() throws MacroException {
    MacroHandler handler = new MacroHandler(ImmutableMap.of());
    String raw = "hello \\$(notamacro hello)";
    String expanded = handler.expand(target, createCellRoots(filesystem), resolver, raw);
    assertEquals("hello $(notamacro hello)", expanded);
  }

  @Test
  public void automaticallyAddsOutputToFileVariant() throws MacroException {
    MacroHandler handler = new MacroHandler(ImmutableMap.of("foo", new StringExpander("cake")));
    String expanded =
        handler.expand(target, createCellRoots(filesystem), resolver, "Hello $(@foo //:test)");

    assertTrue(expanded, expanded.startsWith("Hello @"));
  }

  @Test
  public void testContainsWorkerMacroReturnsTrue() throws MacroException {
    MacroHandler handler = new MacroHandler(ImmutableMap.of("worker", new WorkerMacroExpander()));
    assertTrue(MacroArg.containsWorkerMacro(handler, "$(worker :rule)"));
  }

  @Test
  public void testContainsWorkerMacroReturnsFalse() throws MacroException {
    MacroHandler handler =
        new MacroHandler(
            ImmutableMap.of(
                "worker", new WorkerMacroExpander(),
                "exe", new ExecutableMacroExpander()));
    assertFalse(MacroArg.containsWorkerMacro(handler, "$(exe :rule) not a worker macro in sight"));
  }
}
