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

package com.facebook.buck.jvm.java.abi.source;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import java.io.IOException;
import javax.lang.model.type.WildcardType;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiParameterized.class)
public class StandaloneWildcardTypeTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testUnboundedToString() throws IOException {
    initCompiler();

    WildcardType wildcard = types.getWildcardType(null, null);

    assertEquals("?", wildcard.toString());
  }

  @Test
  public void testExtendsToString() throws IOException {
    initCompiler();

    WildcardType wildcard =
        types.getWildcardType(elements.getTypeElement("java.lang.CharSequence").asType(), null);

    assertEquals("? extends java.lang.CharSequence", wildcard.toString());
  }

  @Test
  public void testSuperToString() throws IOException {
    initCompiler();

    WildcardType wildcard =
        types.getWildcardType(null, elements.getTypeElement("java.lang.String").asType());

    assertEquals("? super java.lang.String", wildcard.toString());
  }
}
