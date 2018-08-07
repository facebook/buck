/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.tools.documentation.generator.skylark.rendering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.util.environment.Platform;
import com.google.common.io.Resources;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class SoyTemplateSkylarkSignatureRendererTest {

  @SkylarkCallable(
      name = "dummy",
      doc = "Returns a dummy list of strings.",
      parameters = {
        @Param(name = "seed", type = String.class, doc = "the first element of the returned list."),
      },
      documented = false,
      useAst = true,
      useEnvironment = true)
  public SkylarkList<String> dummy(String seed) {
    return SkylarkList.createImmutable(Collections.singleton(seed));
  }

  @SkylarkCallable(
      name = "dummy",
      doc = "Returns a dummy list of strings.",
      documented = false,
      useAst = true,
      useEnvironment = true)
  public SkylarkList<String> dummyWithoutArgs() {
    return SkylarkList.createImmutable(Collections.emptyList());
  }

  @SkylarkCallable(
      name = "dummy",
      doc = "Returns a dummy list of strings.",
      extraKeywords = @Param(name = "kwargs", doc = "the dummy attributes."),
      documented = false,
      useAst = true,
      useEnvironment = true)
  public SkylarkList<String> dummyWithKwargs(Map<String, Object> kwargs) {
    return SkylarkList.createImmutable(kwargs.keySet());
  }

  @Before
  public void setUp() {
    // ignore windows and its new line philosophy
    assumeTrue(Platform.detect() != Platform.WINDOWS);
  }

  @Test
  public void rendersAnExpectedFunctionSoyTemplate() throws Exception {
    SoyTemplateSkylarkSignatureRenderer renderer = new SoyTemplateSkylarkSignatureRenderer();
    Method dummyField = getClass().getMethod("dummy", String.class);
    String expectedContent = getExpectedContent("data/expected_dummy_function.soy");
    String actualContent = renderer.render(dummyField.getAnnotation(SkylarkCallable.class));
    assertEquals(expectedContent, actualContent);
  }

  @Test
  public void rendersAnExpectedFunctionSoyTemplateWithKwargs() throws Exception {
    SoyTemplateSkylarkSignatureRenderer renderer = new SoyTemplateSkylarkSignatureRenderer();
    Method dummyField = getClass().getMethod("dummyWithKwargs", Map.class);
    String expectedContent = getExpectedContent("data/expected_dummy_function_with_kwargs.soy");
    String actualContent = renderer.render(dummyField.getAnnotation(SkylarkCallable.class));
    assertEquals(expectedContent, actualContent);
  }

  @Test
  public void rendersAnExpectedFunctionSoyTemplateWithoutArgs() throws Exception {
    SoyTemplateSkylarkSignatureRenderer renderer = new SoyTemplateSkylarkSignatureRenderer();
    Method dummyField = getClass().getMethod("dummyWithoutArgs");
    String expectedContent = getExpectedContent("data/expected_dummy_function_without_args.soy");
    String actualContent = renderer.render(dummyField.getAnnotation(SkylarkCallable.class));
    assertEquals(expectedContent, actualContent);
  }

  @Test
  public void rendersExpectedTableOfContents() throws Exception {
    SoyTemplateSkylarkSignatureRenderer renderer = new SoyTemplateSkylarkSignatureRenderer();
    Method dummyField = getClass().getMethod("dummy", String.class);
    String expectedContent = getExpectedContent("data/expected_dummy_toc.soy");
    String actualContent =
        renderer.renderTableOfContents(
            Collections.singleton(dummyField.getAnnotation(SkylarkCallable.class)));
    assertEquals(expectedContent, actualContent);
  }

  private static String getExpectedContent(String resourceName) throws IOException {
    return Resources.toString(
        Resources.getResource(SoyTemplateSkylarkSignatureRendererTest.class, resourceName),
        StandardCharsets.UTF_8);
  }
}
