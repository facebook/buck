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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

public class MacroHandlerTest {

  @Test
  public void noSuchMacro() {
    MacroHandler handler = new MacroHandler(ImmutableMap.<String, MacroExpander>of());
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    BuildRuleResolver resolver = new BuildRuleResolver();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
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

}
