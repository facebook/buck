/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

public class DexRDotJavaStepTest {

  @Test
  public void testStepsAreCreatedInOrder() throws IOException {
    AndroidPlatformTarget platformTarget = createMock(AndroidPlatformTarget.class);
    expect(platformTarget.getDxExecutable()).andStubReturn(new File("/bin/dx"));
    replay(platformTarget);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//java/base:rule");

    DexRDotJavaStep step = DexRDotJavaStep.create(buildTarget, Paths.get("."));

    String rDotJavaScratchDir = "buck-out/bin/java/base/__rule_r_dot_java_scratch__";
    String rDotJavaClassesTxt = rDotJavaScratchDir + "/classes.txt";
    String rDotJavaDex = rDotJavaScratchDir + "/classes.dex.jar";

    List<String> expectedStepDescriptions = Lists.newArrayList(
        makeCleanDirDescription(rDotJavaScratchDir),
        String.format("get_class_names . > %s", rDotJavaClassesTxt),
        dxStepDescription(rDotJavaDex),
        "estimate_linear_alloc");

    ExecutionContext executionContext = TestExecutionContext.newBuilder()
        .setAndroidPlatformTarget(Optional.of(platformTarget))
        .build();

    assertEquals("DexRDotJavaStep.create() must build up this exact list of steps.",
        Joiner.on(" && ").join(expectedStepDescriptions),
        step.getDescription(executionContext));

        verify(platformTarget);
  }

  private static String makeCleanDirDescription(String dir) {
    String absPath = Paths.get(dir).toAbsolutePath().toString();
    return String.format("rm -r -f %s && mkdir -p %s", absPath, absPath);
  }

  private static String dxStepDescription(String dexfile) {
    String outputPath = Paths.get(".").toAbsolutePath().normalize().toString();
    String jvmFlags = (DxStep.XMX_OVERRIDE.isEmpty() ? null : DxStep.XMX_OVERRIDE);

    return Joiner.on(" ").skipNulls().join(
        "/bin/dx", jvmFlags, "--dex --no-optimize --output", dexfile, outputPath);
  }
}
