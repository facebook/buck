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

package com.facebook.buck.core.model.tc.factory;

import static org.junit.Assert.assertSame;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import org.junit.Test;

public class TargetConfigurationFactoryTest {
  @Test
  public void builtin() {
    TargetConfigurationFactory targetConfigurationFactory =
        new TargetConfigurationFactory(
            new ParsingUnconfiguredBuildTargetViewFactory(),
            TestCellBuilder.createCellRoots(new FakeProjectFilesystem()));
    assertSame(
        UnconfiguredTargetConfiguration.INSTANCE,
        targetConfigurationFactory.create("builtin//platform:unconfigured"));
  }
}
