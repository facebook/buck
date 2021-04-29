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
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.ConstantHostTargetConfigurationResolver;
import com.facebook.buck.core.model.HostTargetConfigurationResolver;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.macros.BuildTargetMacro;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.UnconfiguredBuildTargetMacro;
import com.facebook.buck.rules.macros.UnconfiguredMacro;
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

  private static class UnconfiguredMyBuildTargetMacro extends UnconfiguredBuildTargetMacro {
    private final UnconfiguredBuildTargetWithOutputs buildTarget;

    private UnconfiguredMyBuildTargetMacro(UnconfiguredBuildTargetWithOutputs buildTarget) {
      this.buildTarget = buildTarget;
    }

    @Override
    public UnconfiguredBuildTargetWithOutputs getTargetWithOutputs() {
      return buildTarget;
    }

    @Override
    public Class<? extends UnconfiguredMacro> getUnconfiguredMacroClass() {
      return UnconfiguredMyBuildTargetMacro.class;
    }

    @Override
    public MyBuildTargetMacro configure(
        TargetConfiguration targetConfiguration,
        HostTargetConfigurationResolver hostConfigurationResolver) {
      return new MyBuildTargetMacro(getTargetWithOutputs().configure(targetConfiguration));
    }
  }

  private final TargetConfiguration hostConfiguration =
      RuleBasedTargetConfiguration.of(ConfigurationBuildTargetFactoryForTests.newInstance("//:h"));
  private final TargetConfiguration targetConfiguration =
      RuleBasedTargetConfiguration.of(ConfigurationBuildTargetFactoryForTests.newInstance("//:t"));

  final TypeCoercer<UnconfiguredBuildTargetWithOutputs, BuildTargetWithOutputs>
      buildTargetWithOutputsTypeCoercer =
          new BuildTargetWithOutputsTypeCoercer(
              new UnconfiguredBuildTargetWithOutputsTypeCoercer(
                  new UnconfiguredBuildTargetTypeCoercer(
                      new ParsingUnconfiguredBuildTargetViewFactory())));

  @Test
  public void coercesOutputLabel() throws Exception {
    BuildTargetMacroTypeCoercer<UnconfiguredMyBuildTargetMacro, MyBuildTargetMacro> coercer =
        new BuildTargetMacroTypeCoercer<>(
            buildTargetWithOutputsTypeCoercer,
            UnconfiguredMyBuildTargetMacro.class,
            MyBuildTargetMacro.class,
            UnconfiguredMyBuildTargetMacro::new);
    MyBuildTargetMacro macro =
        coercer.coerceBoth(
            TestCellPathResolver.get(new FakeProjectFilesystem()).getCellNameResolver(),
            new FakeProjectFilesystem(),
            ForwardRelPath.of(""),
            targetConfiguration,
            new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
            ImmutableList.of("//:rrr[label]"));
    Assert.assertEquals(
        BuildTargetFactory.newInstance("//:rrr").getFullyQualifiedName(),
        macro.buildTarget.getBuildTarget().getFullyQualifiedName());
    Assert.assertEquals(OutputLabel.of("label"), macro.buildTarget.getOutputLabel());
  }
}
