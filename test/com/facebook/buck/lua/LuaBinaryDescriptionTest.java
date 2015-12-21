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

package com.facebook.buck.lua;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;

import org.hamcrest.Matchers;
import org.junit.Test;

public class LuaBinaryDescriptionTest {

  @Test
  public void mainModule() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    LuaBinary binary =
        (LuaBinary) new LuaBinaryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setMainModule("hello.world")
            .build(resolver);
    assertThat(binary.getMainModule(), Matchers.equalTo("hello.world"));
  }

  @Test
  public void extensionOverride() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    LuaBinary binary =
        (LuaBinary) new LuaBinaryBuilder(
                BuildTargetFactory.newInstance("//:rule"),
                FakeLuaConfig.DEFAULT
                    .withExtension(".override"))
            .build(resolver);
    assertThat(binary.getBinPath().toString(), Matchers.endsWith(".override"));
  }

  @Test
  public void toolOverride() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    Tool override = new CommandTool.Builder().addArg("override").build();
    LuaBinary binary =
        (LuaBinary) new LuaBinaryBuilder(
            BuildTargetFactory.newInstance("//:rule"),
            FakeLuaConfig.DEFAULT
                .withLua(override)
                .withExtension(".override"))
            .build(resolver);
    assertThat(binary.getLua(), Matchers.is(override));
  }

}
