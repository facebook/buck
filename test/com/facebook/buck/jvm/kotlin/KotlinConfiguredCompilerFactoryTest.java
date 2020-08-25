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

package com.facebook.buck.jvm.kotlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.jvm.java.JavaCompilationConstants;
import com.facebook.buck.jvm.java.JavacFactory;
import com.facebook.buck.jvm.java.JavacFactoryHelper;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;

public class KotlinConfiguredCompilerFactoryTest {
  private KotlinBuckConfig config;
  private JavacFactory jFactory;
  private KotlinConfiguredCompilerFactory kFactory;
  private KotlinLibraryDescriptionArg.Builder kotlinArgsBuilder;

  @Before
  public void setUp() {
    config = new KotlinBuckConfig(FakeBuckConfig.builder().build());
    jFactory = JavacFactoryHelper.createJavacFactory(JavaCompilationConstants.DEFAULT_JAVA_CONFIG);
    kFactory = new KotlinConfiguredCompilerFactory(config, jFactory);
    kotlinArgsBuilder = FauxKotlinLibraryBuilder.createBuilder(
      BuildTargetFactory.newInstance("//:rule")).getArgForPopulating();
  }

  @Test
  public void testCondenseCompilerArguments_validateAllArguments() {
    kotlinArgsBuilder.addFreeCompilerArgs("-test", "-test");
    kotlinArgsBuilder.setAllWarningsAsErrors(true);
    kotlinArgsBuilder.setSuppressWarnings(true);
    kotlinArgsBuilder.setVerbose(true);
    kotlinArgsBuilder.setJvmTarget("1.8");
    kotlinArgsBuilder.setIncludeRuntime(true);
    kotlinArgsBuilder.setJdkHome("jdk_home");
    kotlinArgsBuilder.setNoJdk(true);
    kotlinArgsBuilder.setNoStdlib(true);
    kotlinArgsBuilder.setNoReflect(true);
    kotlinArgsBuilder.setJavaParameters(true);
    kotlinArgsBuilder.setApiVersion("1.3");
    kotlinArgsBuilder.setLanguageVersion("1.3");

    ImmutableList<String> condensedArgs =
      kFactory.condenseCompilerArguments(kotlinArgsBuilder.build());

    assertEquals(condensedArgs.indexOf("-test"), condensedArgs.lastIndexOf("-test"));

    assertTrue(condensedArgs.contains("-Werror"));
    assertTrue(condensedArgs.contains("-nowarn"));
    assertTrue(condensedArgs.contains("-verbose"));
    assertTrue(condensedArgs.contains("-include-runtime"));
    assertTrue(condensedArgs.contains("-no-jdk"));
    assertTrue(condensedArgs.contains("-no-stdlib"));
    assertTrue(condensedArgs.contains("-no-reflect"));
    assertTrue(condensedArgs.contains("-java-parameters"));

    assertTrue(condensedArgs.contains("-jvm-target"));
    int jvmTargetIndex = condensedArgs.indexOf("-jvm-target") + 1;
    assertEquals(condensedArgs.get(jvmTargetIndex), "1.8");

    assertTrue(condensedArgs.contains("-jdk-home"));
    int jdkHomeIndex = condensedArgs.indexOf("-jdk-home") + 1;
    assertEquals(condensedArgs.get(jdkHomeIndex), "jdk_home");

    assertTrue(condensedArgs.contains("-language-version"));
    int languageVersionIndex = condensedArgs.indexOf("-language-version") + 1;
    assertEquals(condensedArgs.get(languageVersionIndex), "1.3");

    assertTrue(condensedArgs.contains("-api-version"));
    int apiVersionIndex = condensedArgs.indexOf("-api-version") + 1;
    assertEquals(condensedArgs.get(apiVersionIndex), "1.3");
    
  }
}
