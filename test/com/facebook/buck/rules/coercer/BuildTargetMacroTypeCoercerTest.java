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
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
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
    private final BuildTargetWithOutputs buildTarget;

    private MyBuildTargetMacro(BuildTargetWithOutputs buildTarget) {
      this.buildTarget = buildTarget;
    }

    @Override
    public Class<? extends Macro> getMacroClass() {
      return MyBuildTargetMacro.class;
    }

    @Override
    public BuildTargetMacro withTargetWithOutputs(BuildTargetWithOutputs target) {
      throw new AssertionError();
    }

    @Override
    public BuildTargetWithOutputs getTargetWithOutputs() {
      return buildTarget;
    }
  }

  private TargetConfiguration hostConfiguration =
      RuleBasedTargetConfiguration.of(ConfigurationBuildTargetFactoryForTests.newInstance("//:h"));
  private TargetConfiguration targetConfiguration =
      RuleBasedTargetConfiguration.of(ConfigurationBuildTargetFactoryForTests.newInstance("//:t"));

  TypeCoercer<UnconfiguredBuildTargetWithOutputs, BuildTargetWithOutputs>
      buildTargetWithOutputsTypeCoercer =
          new BuildTargetWithOutputsTypeCoercer(
              new UnconfiguredBuildTargetWithOutputsTypeCoercer(
                  new UnconfiguredBuildTargetTypeCoercer(
                      new ParsingUnconfiguredBuildTargetViewFactory())));

  @Test
  public void useHost() throws Exception {
    BuildTargetMacroTypeCoercer<MyBuildTargetMacro> coercer =
        new BuildTargetMacroTypeCoercer<>(
            buildTargetWithOutputsTypeCoercer,
            MyBuildTargetMacro.class,
            BuildTargetMacroTypeCoercer.TargetOrHost.HOST,
            MyBuildTargetMacro::new);
    MyBuildTargetMacro macro =
        coercer.coerce(
            TestCellPathResolver.get(new FakeProjectFilesystem()).getCellNameResolver(),
            new FakeProjectFilesystem(),
            ForwardRelativePath.of(""),
            targetConfiguration,
            hostConfiguration,
            ImmutableList.of("//:rrr"));
    Assert.assertEquals(
        hostConfiguration, macro.buildTarget.getBuildTarget().getTargetConfiguration());
    Assert.assertEquals(
        BuildTargetFactory.newInstance("//:rrr").getFullyQualifiedName(),
        macro.buildTarget.getBuildTarget().getFullyQualifiedName());
    Assert.assertEquals(OutputLabel.defaultLabel(), macro.buildTarget.getOutputLabel());
    Assert.assertEquals(
        BuildTargetFactory.newInstance("//:rrr").getFullyQualifiedName(),
        macro.buildTarget.getBuildTarget().getFullyQualifiedName());
    Assert.assertEquals(OutputLabel.defaultLabel(), macro.buildTarget.getOutputLabel());
  }

  @Test
  public void useTarget() throws Exception {
    BuildTargetMacroTypeCoercer<MyBuildTargetMacro> coercer =
        new BuildTargetMacroTypeCoercer<>(
            buildTargetWithOutputsTypeCoercer,
            MyBuildTargetMacro.class,
            BuildTargetMacroTypeCoercer.TargetOrHost.TARGET,
            MyBuildTargetMacro::new);
    MyBuildTargetMacro macro =
        coercer.coerce(
            TestCellPathResolver.get(new FakeProjectFilesystem()).getCellNameResolver(),
            new FakeProjectFilesystem(),
            ForwardRelativePath.of(""),
            targetConfiguration,
            hostConfiguration,
            ImmutableList.of("//:rrr"));
    Assert.assertEquals(
        targetConfiguration, macro.buildTarget.getBuildTarget().getTargetConfiguration());
  }

  @Test
  public void coercesOutputLabel() throws Exception {
    BuildTargetMacroTypeCoercer<MyBuildTargetMacro> coercer =
        new BuildTargetMacroTypeCoercer<>(
            buildTargetWithOutputsTypeCoercer,
            MyBuildTargetMacro.class,
            BuildTargetMacroTypeCoercer.TargetOrHost.HOST,
            MyBuildTargetMacro::new);
    MyBuildTargetMacro macro =
        coercer.coerce(
            TestCellPathResolver.get(new FakeProjectFilesystem()).getCellNameResolver(),
            new FakeProjectFilesystem(),
            ForwardRelativePath.of(""),
            targetConfiguration,
            hostConfiguration,
            ImmutableList.of("//:rrr[label]"));
    Assert.assertEquals(
        BuildTargetFactory.newInstance("//:rrr").getFullyQualifiedName(),
        macro.buildTarget.getBuildTarget().getFullyQualifiedName());
    Assert.assertEquals(OutputLabel.of("label"), macro.buildTarget.getOutputLabel());
  }
}
