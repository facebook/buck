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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.ImmutableRuleBasedTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.macros.BuildTargetMacro;
import com.facebook.buck.rules.macros.Macro;
import com.google.common.collect.ImmutableList;
import org.junit.Assert;
import org.junit.Test;

public class BuildTargetMacroTypeCoercerTest {

  private static class MyBuildTargetMacro extends BuildTargetMacro {
    private final BuildTarget buildTarget;

    private MyBuildTargetMacro(BuildTarget buildTarget) {
      this.buildTarget = buildTarget;
    }

    @Override
    public Class<? extends Macro> getMacroClass() {
      return MyBuildTargetMacro.class;
    }

    @Override
    public BuildTargetMacro withTarget(BuildTarget target) {
      throw new AssertionError();
    }

    @Override
    public BuildTarget getTarget() {
      return buildTarget;
    }
  }

  private TargetConfiguration hostConfiguration =
      ImmutableRuleBasedTargetConfiguration.of(
          ConfigurationBuildTargetFactoryForTests.newInstance("//:h"));
  private TargetConfiguration targetConfiguration =
      ImmutableRuleBasedTargetConfiguration.of(
          ConfigurationBuildTargetFactoryForTests.newInstance("//:t"));

  BuildTargetTypeCoercer buildTargetTypeCoercer =
      new BuildTargetTypeCoercer(
          new UnconfiguredBuildTargetTypeCoercer(new ParsingUnconfiguredBuildTargetViewFactory()));

  @Test
  public void useHost() throws Exception {
    BuildTargetMacroTypeCoercer<MyBuildTargetMacro> coercer =
        new BuildTargetMacroTypeCoercer<>(
            buildTargetTypeCoercer,
            MyBuildTargetMacro.class,
            BuildTargetMacroTypeCoercer.TargetOrHost.HOST,
            MyBuildTargetMacro::new);
    MyBuildTargetMacro macro =
        coercer.coerce(
            TestCellPathResolver.get(new FakeProjectFilesystem()),
            new FakeProjectFilesystem(),
            ForwardRelativePath.of(""),
            targetConfiguration,
            hostConfiguration,
            ImmutableList.of("//:rrr"));
    Assert.assertEquals(hostConfiguration, macro.buildTarget.getTargetConfiguration());
  }

  @Test
  public void useTarget() throws Exception {
    BuildTargetMacroTypeCoercer<MyBuildTargetMacro> coercer =
        new BuildTargetMacroTypeCoercer<>(
            buildTargetTypeCoercer,
            MyBuildTargetMacro.class,
            BuildTargetMacroTypeCoercer.TargetOrHost.TARGET,
            MyBuildTargetMacro::new);
    MyBuildTargetMacro macro =
        coercer.coerce(
            TestCellPathResolver.get(new FakeProjectFilesystem()),
            new FakeProjectFilesystem(),
            ForwardRelativePath.of(""),
            targetConfiguration,
            hostConfiguration,
            ImmutableList.of("//:rrr"));
    Assert.assertEquals(targetConfiguration, macro.buildTarget.getTargetConfiguration());
  }
}
