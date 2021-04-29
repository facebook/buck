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

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.ConstantHostTargetConfigurationResolver;
import com.facebook.buck.core.model.HostTargetConfigurationResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.UnconfiguredMacro;
import com.google.common.collect.ImmutableList;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ZeroArgMacroTypeCoercerTest {

  @Test
  public void test() throws CoerceFailedException {
    ForwardRelPath basePath = ForwardRelPath.of("java/com/facebook/buck/example");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ZeroArgMacro macro = new ZeroArgMacro();
    ZeroArgMacroTypeCoercer<ZeroArgMacro, ZeroArgMacro> coercer =
        new ZeroArgMacroTypeCoercer<>(ZeroArgMacro.class, ZeroArgMacro.class, macro);
    ZeroArgMacro result =
        coercer.coerceBoth(
            createCellRoots(filesystem).getCellNameResolver(),
            filesystem,
            basePath,
            UnconfiguredTargetConfiguration.INSTANCE,
            new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
            ImmutableList.of());
    assertThat(result, Matchers.is(macro));
  }

  @Test(expected = CoerceFailedException.class)
  public void testIncorrectArgs() throws CoerceFailedException {
    ForwardRelPath basePath = ForwardRelPath.of("java/com/facebook/buck/example");
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ZeroArgMacroTypeCoercer<ZeroArgMacro, ZeroArgMacro> coercer =
        new ZeroArgMacroTypeCoercer<>(ZeroArgMacro.class, ZeroArgMacro.class, new ZeroArgMacro());
    coercer.coerceBoth(
        createCellRoots(filesystem).getCellNameResolver(),
        filesystem,
        basePath,
        UnconfiguredTargetConfiguration.INSTANCE,
        new ConstantHostTargetConfigurationResolver(UnconfiguredTargetConfiguration.INSTANCE),
        ImmutableList.of("arg"));
  }

  private static class ZeroArgMacro implements Macro, UnconfiguredMacro {

    @Override
    public Class<? extends UnconfiguredMacro> getUnconfiguredMacroClass() {
      return ZeroArgMacro.class;
    }

    @Override
    public Class<? extends Macro> getMacroClass() {
      return ZeroArgMacro.class;
    }

    @Override
    public Macro configure(
        TargetConfiguration targetConfiguration,
        HostTargetConfigurationResolver hostConfigurationResolver) {
      return this;
    }
  }
}
