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

package com.facebook.buck.jvm.java;

import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Optional;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class JavacSpecTest {
  private ActionGraphBuilder graphBuilder;
  private JavacSpec spec = JavacSpec.of();

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() {
    graphBuilder = new TestActionGraphBuilder();
  }

  @Test
  public void returnsBuiltInJavacByDefault() {
    Javac javac = getJavac();

    assertTrue(javac instanceof JdkProvidedInMemoryJavac);
  }

  @Test
  public void returnsExternalCompilerIfJavacPathPresent() throws IOException {
    // newExecutableFile cannot be executed on windows.
    Assume.assumeThat(Platform.detect(), not(Platform.WINDOWS));
    AbsPath externalPath = tmp.newExecutableFile();

    SourcePath javacPath = FakeSourcePath.of(externalPath);
    spec = JavacSpec.of(Optional.of(javacPath));
    ExternalJavac.ResolvedExternalJavac javac =
        (ExternalJavac.ResolvedExternalJavac)
            getJavac().resolve(graphBuilder.getSourcePathResolver(), tmp.getRoot());

    assertEquals(ImmutableList.of(externalPath.toString()), javac.getCommandPrefix());
  }

  private Javac getJavac() {
    return spec.getJavacProvider().resolve(graphBuilder);
  }
}
