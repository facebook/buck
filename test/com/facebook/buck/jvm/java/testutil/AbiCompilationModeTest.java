/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.testutil;

import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Base class for tests that want to run as @Parameterized to cover both cases where compiling
 * against ABIs is on and off
 */
@RunWith(Parameterized.class)
public abstract class AbiCompilationModeTest {

  @Parameterized.Parameter public String compileAgainstAbis;

  protected static final String FALSE = "Compiling against full jars";
  protected static final String TRUE = "Compiling against ABI jars";

  @Parameterized.Parameters(name = "{0}")
  public static Object[] getParameters() {
    return new Object[] {TRUE, FALSE};
  }

  protected JavaBuckConfig getJavaBuckConfigWithCompilationMode() {
    return JavaBuckConfig.of(
        FakeBuckConfig.builder()
            .setSections(
                "[" + JavaBuckConfig.SECTION + "]",
                JavaBuckConfig.PROPERTY_COMPILE_AGAINST_ABIS
                    + " = "
                    + compileAgainstAbis.equals(TRUE))
            .build());
  }

  protected void setWorkspaceCompilationMode(ProjectWorkspace projectWorkspace) throws IOException {
    projectWorkspace.addBuckConfigLocalOption(
        JavaBuckConfig.SECTION,
        JavaBuckConfig.PROPERTY_COMPILE_AGAINST_ABIS,
        Boolean.toString(compileAgainstAbis.equals(TRUE)));
  }

  protected void compileAgainstAbisOnly() {
    assumeThat(compileAgainstAbis, Matchers.equalTo(TRUE));
  }
}
