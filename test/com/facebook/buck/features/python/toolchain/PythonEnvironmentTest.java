/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.features.python.toolchain;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.impl.ImmutableDefaultCellPathResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.rules.modern.SerializationTestHelper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class PythonEnvironmentTest {

  @Test
  public void testSerialization() throws IOException {
    String configSection = "python#pypypy";
    PythonVersion pythonVersion = PythonVersion.of("python", "pypypy-3");
    Path pythonPath = Paths.get("pypypy");
    Path otherPythonPath = Paths.get("pppyyy");
    PythonEnvironment environment = new PythonEnvironment(pythonPath, pythonVersion, configSection);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    Path root = Paths.get("root");
    CellPathResolver cellResolver = ImmutableDefaultCellPathResolver.of(root, ImmutableMap.of());
    PythonEnvironment reconstructed =
        SerializationTestHelper.serializeAndDeserialize(
            environment,
            PythonEnvironment.class,
            ruleFinder,
            cellResolver,
            DefaultSourcePathResolver.from(ruleFinder),
            new ToolchainProviderBuilder()
                .withToolchain(
                    PythonInterpreter.DEFAULT_NAME,
                    new PythonInterpreter() {
                      @Override
                      public Path getPythonInterpreterPath(String section) {
                        Preconditions.checkArgument(section.equals(configSection));
                        return otherPythonPath;
                      }

                      @Override
                      public Path getPythonInterpreterPath() {
                        throw new RuntimeException();
                      }
                    })
                .build(),
            cellName -> {
              throw new RuntimeException();
            });

    assertEquals("pppyyy", reconstructed.getPythonPath().toString());
    assertEquals("python", reconstructed.getPythonVersion().getInterpreterName());
    assertEquals("pypypy-3", reconstructed.getPythonVersion().getVersionString());
  }
}
