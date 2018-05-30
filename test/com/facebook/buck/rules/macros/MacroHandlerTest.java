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

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.model.macros.MacroMatchResult;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MacroHandlerTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private ProjectFilesystem filesystem;
  private BuildTarget target;
  private ActionGraphBuilder graphBuilder;

  @Before
  public void before() throws Exception {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.newFolder().toPath());
    target = BuildTargetFactory.newInstance("//:test");
    JavaLibraryBuilder builder = JavaLibraryBuilder.createBuilder(target);
    graphBuilder = new TestActionGraphBuilder(TargetGraphFactory.newInstance(builder.build()));
    builder.build(graphBuilder, filesystem);
  }

  @Test
  public void noSuchMacro() {
    MacroHandler handler = new MacroHandler(ImmutableMap.of());
    try {
      handler.expand(target, createCellRoots(filesystem), graphBuilder, "$(badmacro hello)");
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
    String expanded = handler.expand(target, createCellRoots(filesystem), graphBuilder, raw);
    assertEquals("hello $(notamacro hello)", expanded);
  }

  @Test
  public void automaticallyAddsOutputToFileVariant() throws MacroException {
    MacroHandler handler =
        new MacroHandler(
            ImmutableMap.of("foo", new StringExpander<>(Macro.class, StringArg.of("cake"))));
    String expanded =
        handler.expand(target, createCellRoots(filesystem), graphBuilder, "Hello $(@foo //:test)");

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

  @Test
  public void testHandlingMacroExpanderWithPrecomputedWork() throws Exception {
    AtomicInteger expansionCount = new AtomicInteger(0);
    AbstractMacroExpander<String, String> expander =
        new AbstractMacroExpander<String, String>() {
          @Override
          public Class<String> getInputClass() {
            return String.class;
          }

          @Override
          public Class<String> getPrecomputedWorkClass() {
            return String.class;
          }

          @Override
          protected String parse(
              BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input) {
            return "Parsed Result";
          }

          @Override
          public String precomputeWorkFrom(
              BuildTarget target,
              CellPathResolver cellNames,
              ActionGraphBuilder graphBuilder,
              String input) {
            expansionCount.incrementAndGet();
            return "Precomputed Work";
          }

          @Override
          public Arg expandFrom(
              BuildTarget target,
              CellPathResolver cellNames,
              ActionGraphBuilder graphBuilder,
              String input,
              String precomputedWork) {
            return StringArg.of(precomputedWork);
          }
        };
    MacroHandler handler = new MacroHandler(ImmutableMap.of("e", expander));

    Map<MacroMatchResult, Object> cache = new HashMap<>();
    CellPathResolver cellRoots = createCellRoots(filesystem);
    String raw = "$(e)";
    assertEquals("Precomputed Work", handler.expand(target, cellRoots, graphBuilder, raw, cache));
    assertEquals("Precomputed Work", handler.expand(target, cellRoots, graphBuilder, raw, cache));

    // We should have only done work once, despite calling expand twice
    assertEquals(1, expansionCount.get());
    // The cache should contain our entry:
    assertEquals(1, cache.size());
    assertThat(cache.values(), Matchers.contains("Precomputed Work"));

    // Let's check the other operations and ensure that the precomputed work stays cached.
    handler.extractBuildTimeDeps(target, cellRoots, graphBuilder, raw, cache);
    handler.extractRuleKeyAppendables(target, cellRoots, graphBuilder, raw, cache);

    assertEquals(1, expansionCount.get());

    // Now call without the cache, and ensure that the work gets recomputed
    assertEquals("Precomputed Work", handler.expand(target, cellRoots, graphBuilder, raw));
    assertEquals(2, expansionCount.get());

    // Now clear the cache, and ensure it's repopulated:
    cache.clear();
    assertEquals("Precomputed Work", handler.expand(target, cellRoots, graphBuilder, raw, cache));
    assertEquals(3, expansionCount.get());
    assertThat(cache.values(), Matchers.contains("Precomputed Work"));
  }
}
