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

package com.facebook.buck.core.model.impl;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Test;

public class JsonTargetConfigurationSerializerTest {

  private Function<String, UnconfiguredBuildTarget> buildTargetProvider;

  @Before
  public void setUp() {
    UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory =
        new ParsingUnconfiguredBuildTargetViewFactory();
    CellPathResolver cellPathResolver = TestCellPathResolver.get(new FakeProjectFilesystem());
    buildTargetProvider =
        buildTarget ->
            unconfiguredBuildTargetFactory.create(
                buildTarget, cellPathResolver.getCellNameResolver());
  }

  @Test
  public void emptyTargetConfigurationSerializesToString() {
    assertEquals(
        "{}",
        new JsonTargetConfigurationSerializer(buildTargetProvider)
            .serialize(UnconfiguredTargetConfiguration.INSTANCE));
  }

  @Test
  public void defaultTargetConfigurationSerializesToString() {
    assertEquals(
        "{\"targetPlatform\":\"//platform:platform\"}",
        new JsonTargetConfigurationSerializer(buildTargetProvider)
            .serialize(
                RuleBasedTargetConfiguration.of(
                    ConfigurationBuildTargetFactoryForTests.newInstance("//platform:platform"))));
  }

  @Test
  public void emptyTargetConfigurationDeserializesFromString() {
    assertEquals(
        UnconfiguredTargetConfiguration.INSTANCE,
        new JsonTargetConfigurationSerializer(buildTargetProvider).deserialize("{}"));
  }

  @Test
  public void defaultTargetConfigurationDeserializedFromString() {
    RuleBasedTargetConfiguration targetConfiguration =
        (RuleBasedTargetConfiguration)
            new JsonTargetConfigurationSerializer(buildTargetProvider)
                .deserialize("{\"targetPlatform\":\"//platform:platform\"}");

    assertEquals("//platform:platform", targetConfiguration.getTargetPlatform().toString());
  }
}
