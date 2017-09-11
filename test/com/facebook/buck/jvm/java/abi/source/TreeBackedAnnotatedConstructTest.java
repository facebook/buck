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

package com.facebook.buck.jvm.java.abi.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import com.google.common.base.Joiner;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class TreeBackedAnnotatedConstructTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testGetAnnotationForStringValue() throws IOException {
    compile(Joiner.on('\n').join("@SuppressWarnings(\"42\")", "public class Foo {", "}"));

    SuppressWarnings anno = elements.getTypeElement("Foo").getAnnotation(SuppressWarnings.class);
    assertThat(anno.value(), Matchers.arrayContaining("42"));
  }

  @Test
  public void testGetAnnotationsForStringValue() throws IOException {
    compile(Joiner.on('\n').join("@SuppressWarnings(\"42\")", "public class Foo {", "}"));

    SuppressWarnings[] annos =
        elements.getTypeElement("Foo").getAnnotationsByType(SuppressWarnings.class);
    assertEquals(1, annos.length);
    assertThat(annos[0].value(), Matchers.arrayContaining("42"));
  }
}
