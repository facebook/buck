/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.java;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.IdentityPathAbsolutifier;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.junit.Test;

import java.util.Collections;

public class JavacOptionsTest {

  @Test
  public void buildsAreDebugByDefault() {
    JavacOptions options = JavacOptions.builder().build();

    assertOptionsContains(options, "-g");
  }

  @Test
  public void productionBuildsCanBeEnabled() {
    JavacOptions options = JavacOptions.builder()
        .setProductionBuild()
        .build();

    assertOptionsDoesNotContain(options, "-g");
  }

  @Test
  public void testDoesNotSetBootclasspathByDefault() {
    JavacOptions options = JavacOptions.builder().build();

    assertOptionsDoesNotContain(options, "-bootclasspath");
  }

  @Test
  public void canSetBootclasspath() {
    JavacOptions options = JavacOptions.builder()
        .setBootclasspath("foo:bar")
        .build();

    assertOptionsContains(options, "-bootclasspath foo:bar");
  }

  @Test
  public void shouldSetTheAnnotationSource() {
    AnnotationProcessingParams params = new AnnotationProcessingParams.Builder()
        .addAllProcessors(Collections.singleton("processor"))
        .setProcessOnly(true)
        .build();

    JavacOptions options = JavacOptions.builder()
        .setAnnotationProcessingData(params)
        .build();

    assertOptionsContains(options, "-proc:only");
  }

  @Test
  public void shouldAddAllAddedAnnotationProcessors() {
    AnnotationProcessingParams params = new AnnotationProcessingParams.Builder()
        .addAllProcessors(Lists.newArrayList("myproc", "theirproc"))
        .setProcessOnly(true)
        .build();

    JavacOptions options = JavacOptions.builder()
        .setAnnotationProcessingData(params)
        .build();

    assertOptionsContains(options, "-processor myproc,theirproc");
  }


  private void assertOptionsContains(JavacOptions options, String param) {
    String output = optionsAsString(options);

    assertTrue(String.format("Unable to find: %s in %s", param, output),
        output.contains(" " + param + " "));
  }

  private void assertOptionsDoesNotContain(JavacOptions options, String param) {
    String output = optionsAsString(options);

    assertFalse(String.format("Surprisingly and unexpectedly found: %s in %s", param, output),
        output.contains(" " + param + " "));
  }

  private String optionsAsString(JavacOptions options) {
    ImmutableList.Builder<String> paramBuilder = ImmutableList.builder();

    options.appendOptionsToList(
        paramBuilder, /* pathAbsolutifier */ IdentityPathAbsolutifier.getIdentityAbsolutifier());

    ImmutableList<String> params = paramBuilder.build();
    return " " + Joiner.on(" ").join(params) + " ";
  }
}
