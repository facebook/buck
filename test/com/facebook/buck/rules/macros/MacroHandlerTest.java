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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

public class MacroHandlerTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private ProjectFilesystem filesystem;
  private BuildTarget target;
  private BuildRuleResolver resolver;

  @Before
  public void before() throws IOException {
    filesystem = new ProjectFilesystem(tmp.newFolder().toPath());
    target = BuildTargetFactory.newInstance("//:test");
    resolver = new BuildRuleResolver();
  }

  @Test
  public void noSuchMacro() {
    MacroHandler handler = new MacroHandler(ImmutableMap.<String, MacroExpander>of());
    try {
      handler.expand(target, resolver, filesystem, "$(badmacro hello)");
    } catch (MacroException e) {
      assertTrue(e.getMessage().contains("no such macro \"badmacro\""));
    }
    try {
      handler.extractTargets(target, "$(badmacro hello)");
    } catch (MacroException e) {
      assertTrue(e.getMessage().contains("no such macro \"badmacro\""));
    }
  }

  @Test
  public void escapeMacro() throws MacroException {
    MacroHandler handler = new MacroHandler(ImmutableMap.<String, MacroExpander>of());
    String raw = "hello \\$(notamacro hello)";
    String expanded = handler.expand(target, resolver, filesystem, raw);
    assertEquals("hello $(notamacro hello)", expanded);
  }

  @Test
  public void automaticallyAddsOutputToFileVariant() throws MacroException {
    MacroHandler handler =
        new MacroHandler(ImmutableMap.<String, MacroExpander>of("foo", new StringExpander("cake")));
    String expanded = handler.expand(target, resolver, filesystem, "Hello $(@foo //:test)");

    assertTrue(expanded, expanded.startsWith("Hello @"));
  }

}
