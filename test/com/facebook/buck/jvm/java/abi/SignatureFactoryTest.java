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

package com.facebook.buck.jvm.java.abi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.facebook.buck.jvm.java.testutil.CompilerTreeApiTest;
import com.facebook.buck.jvm.java.testutil.CompilerTreeApiTestRunner;
import com.google.common.base.Joiner;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Map;

@RunWith(CompilerTreeApiTestRunner.class)
public class SignatureFactoryTest extends CompilerTreeApiTest {
  private SignatureFactory signatureFactory;

  @Test
  public void testSignatureOfNonGenericClassIsNull() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "abstract class Foo implements Runnable {}"));
    assertNull(signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfSimpleGenericClass() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "class Foo<T> {}"));
    assertEquals(
        "<T:Ljava/lang/Object;>Ljava/lang/Object;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfSimpleGenericInterface() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "interface Foo<T> {}"));
    assertEquals(
        "<T:Ljava/lang/Object;>Ljava/lang/Object;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfClassWithTypeVariableParameterizedSuperclass() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "abstract class Foo<T> extends java.util.ArrayList<T> {}"));
    assertEquals(
        "<T:Ljava/lang/Object;>Ljava/util/ArrayList<TT;>;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfClassWithWildcardParameterizedSuperclass() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "abstract class Foo extends java.util.ArrayList<?> {}"));
    assertEquals(
        "Ljava/util/ArrayList<*>;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfClassWithSuperWildcardParameterizedSuperclass() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "abstract class Foo extends java.util.ArrayList<? super String> {}"));
    assertEquals(
        "Ljava/util/ArrayList<-Ljava/lang/String;>;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfClassWithExtendsWildcardParameterizedSuperclass() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "abstract class Foo extends java.util.ArrayList<? extends Runnable> {}"));
    assertEquals(
        "Ljava/util/ArrayList<+Ljava/lang/Runnable;>;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfClassWithParameterizedSuperclass() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "abstract class Foo extends java.util.ArrayList<Integer> {}"));
    assertEquals(
        "Ljava/util/ArrayList<Ljava/lang/Integer;>;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfClassWithParameterizedInterfaces() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "abstract class Foo implements java.util.List<Integer>, java.util.Set<Integer> {}"));
    assertEquals(
        Joiner.on("").join(
            "Ljava/lang/Object;",
            "Ljava/util/List<Ljava/lang/Integer;>;",
            "Ljava/util/Set<Ljava/lang/Integer;>;"),
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfClassWithInterfaceBoundedTypeParameter() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "class Foo <T extends Runnable & java.util.Collection> { }"));
    assertEquals(
        "<T::Ljava/lang/Runnable;:Ljava/util/Collection;>Ljava/lang/Object;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfClassWithClassBoundedTypeParameter() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "class Foo <T extends java.util.ArrayList> { }"));
    assertEquals(
        "<T:Ljava/util/ArrayList;>Ljava/lang/Object;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfClassWithTypeVarBoundedTypeParameter() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "class Foo <T extends U, U extends java.util.ArrayList> { }"));
    assertEquals(
        "<T:TU;U:Ljava/util/ArrayList;>Ljava/lang/Object;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfPrimitiveArrayType() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "class Foo extends java.util.ArrayList<int[]> { }"));

    assertEquals(
        "Ljava/util/ArrayList<[I>;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfNestedClassType() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "class Foo extends java.util.HashSet<java.util.Map.Entry> { }"));

    assertEquals(
        "Ljava/util/HashSet<Ljava/util/Map$Entry;>;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo")));
  }

  @Test
  public void testSignatureOfGenericNestedClassTypeInsideGeneric() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "class Foo<T> {",
        "  class Bar<U> { }",
        "  class Baz<V> extends Bar<V> { }",
        "}"));

    assertEquals(
        "<V:Ljava/lang/Object;>Lcom/facebook/foo/Foo<TT;>.Bar<TV;>;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo.Baz")));
  }

  @Test
  public void testSignatureOfGenericNestedClassTypeInsideNonGeneric() throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "class Foo {",
        "  class Bar<U> { }",
        "  class Baz<V> extends Bar<V> { }",
        "}"));

    assertEquals(
        "<V:Ljava/lang/Object;>Lcom/facebook/foo/Foo$Bar<TV;>;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo.Baz")));
  }

  @Test
  public void testSignatureOfNonGenericNestedClassTypeInsideGenericInsideNonGeneric()
      throws IOException {
    compile(Joiner.on('\n').join(
        "package com.facebook.foo;",
        "class Foo {",
        "  class Bar<U> {",
        "    class Inner { }",
        "  }",
        "  class Baz<V> extends Bar<V>.Inner { }",
        "}"));

    assertEquals(
        "<V:Ljava/lang/Object;>Lcom/facebook/foo/Foo$Bar<TV;>.Inner;",
        signatureFactory.getSignature(elements.getTypeElement("com.facebook.foo.Foo.Baz")));
  }

  @Override
  protected CompilerTreeApiFactory initCompiler(
      Map<String, String> fileNamesToContents) throws IOException {
    CompilerTreeApiFactory result = super.initCompiler(fileNamesToContents);
    signatureFactory = new SignatureFactory(new DescriptorFactory(elements));
    return result;
  }
}
