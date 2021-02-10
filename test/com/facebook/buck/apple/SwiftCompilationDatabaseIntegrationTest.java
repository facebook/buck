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

package com.facebook.buck.apple;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.cxx.CxxCompilationDatabaseEntry;
import com.facebook.buck.cxx.CxxCompilationDatabaseUtils;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SwiftCompilationDatabaseIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private ProjectWorkspace workspace;

  @Before
  public void setupWorkspace() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.is(Platform.MACOS));

    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "apple_library_swift_compilation_db", tmp);
    workspace.addBuckConfigLocalOption("apple", "use_swift_delegate", "false");
    workspace.addBuckConfigLocalOption("apple", "compilation_database_includes_swift", "true");
    workspace.setUp();
  }

  @Test
  public void testCreateCompilationDatabaseForAppleLibraryWithNoDeps() throws IOException {
    BuildTarget target =
        BuildTargetFactory.newInstance("//:Bar#compilation-database,iphonesimulator-x86_64");
    Path compilationDatabase = workspace.buildAndReturnOutput(target.getFullyQualifiedName());

    Map<String, CxxCompilationDatabaseEntry> fileToEntry =
        CxxCompilationDatabaseUtils.parseCompilationDatabaseJsonFile(compilationDatabase);

    String expectedFileKey = "Bar.swift|Baz.swift";
    assertTrue(fileToEntry.containsKey(expectedFileKey));

    CxxCompilationDatabaseEntry entry = fileToEntry.get(expectedFileKey);

    Path workingDir = Paths.get(entry.getDirectory());
    assertTrue(Files.exists(workingDir));

    ImmutableList<String> args = entry.getArguments();
    for (String arg : args) {
      if (arg.contains("Foo#apple-swift-compile") && arg.contains("buck-out")) {
        // This would match Swift deps like .swiftmodule files, header maps etc. Any Swift deps
        // must have been materialized, so the compilation command can run successfully.
        Path dependencyFilePath = workingDir.resolve(arg);
        assertTrue(Files.exists(dependencyFilePath));
      }
    }

    assertTrue(args.contains("Bar.swift"));
    assertTrue(args.contains("Baz.swift"));
    assertTrue(Files.exists(workingDir.resolve("Bar.swift")));
    assertTrue(Files.exists(workingDir.resolve("Baz.swift")));
  }
}
