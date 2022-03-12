/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.swift;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.swift.toolchain.ExplicitModuleOutput;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableSet;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SwiftModuleMapFileStepTest {

  @Rule public final TemporaryPaths tmpDir = new TemporaryPaths();

  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = new FakeProjectFilesystem(tmpDir.getRoot());
  }

  @Test
  public void testSwiftModuleMapOutput() throws IOException {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();

    File tempFile = File.createTempFile("swift_module_map.json", "");
    var outputFilePath = Optional.of(tempFile.toPath()).get();

    ExplicitModuleOutput foo =
        ExplicitModuleOutput.of("Foo", true, FakeSourcePath.of("path/to/Foo.swiftmodule"));
    ExplicitModuleOutput bar =
        ExplicitModuleOutput.of("Bar", false, FakeSourcePath.of("path/to/Bar.swiftmodule"));
    ExplicitModuleOutput swiftUI =
        ExplicitModuleOutput.of(
            "SwiftUI", true, FakeSourcePath.of("path/to/SwiftUI.swiftmodule"), true);

    ImmutableSet<ExplicitModuleOutput> moduleDeps = ImmutableSet.of(foo, bar, swiftUI);

    SwiftModuleMapFileStep swiftModuleMapFileStep =
        new SwiftModuleMapFileStep(outputFilePath, moduleDeps, pathResolver, projectFilesystem);

    ByteArrayOutputStream expectedOutput = new ByteArrayOutputStream();
    try (PrintStream sink = new PrintStream(expectedOutput);
        JsonGenerator generator = ObjectMappers.createGenerator(sink).useDefaultPrettyPrinter()) {
      swiftModuleMapFileStep.writeFile(generator);
    }

    assertEquals(
        "[ {\n"
            + "  \"moduleName\" : \"Foo\",\n"
            + "  \"modulePath\" : \"path/to/Foo.swiftmodule\",\n"
            + "  \"isFramework\" : false\n"
            + "}, {\n"
            + "  \"moduleName\" : \"SwiftUI\",\n"
            + "  \"modulePath\" : \"path/to/SwiftUI.swiftmodule\",\n"
            + "  \"isFramework\" : true\n"
            + "} ]",
        expectedOutput.toString());
  }
}
