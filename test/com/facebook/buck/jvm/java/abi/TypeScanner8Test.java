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

import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.facebook.buck.jvm.java.testutil.compiler.TestCompiler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.NullType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiTestRunner.class)
public class TypeScanner8Test {
  @Rule public TestCompiler compiler = new TestCompiler();

  @Test
  public void testScansPrimitives() throws IOException {
    new Tester().scanType("int").assertScans("int");
  }

  @Test
  public void testScansPrimitiveArrays() throws IOException {
    new Tester().scanType("int[][]").assertScans("int[][]", "int[]", "int");
  }

  @Test
  public void testScansDeclaredTypes() throws IOException {
    new Tester().scanType("Object").assertScans("java.lang.Object", "none");
  }

  @Test
  public void testScansNestedTypes() throws IOException {
    new Tester()
        .addExtraCode("private static class Inner { }")
        .scanType("Inner")
        .assertScans("Foo.Inner", "none");
  }

  @Test
  public void testScansInnerTypes() throws IOException {
    new Tester()
        .addExtraCode("private class Inner { }")
        .scanType("Inner")
        .assertScans("Foo.Inner", "Foo", "none");
  }

  @Test
  public void testScansTypeVars() throws IOException {
    new Tester()
        .defineTypeParameter("T extends Runnable")
        .scanType("T")
        .assertScans("T", "<nulltype>", "java.lang.Runnable", "none");
  }

  @Test
  public void testScansIntersectionTypes() throws IOException {
    new Tester()
        .defineTypeParameter("T extends CharSequence & Runnable")
        .scanType("T")
        .assertScans(
            "T",
            "<nulltype>",
            "java.lang.Object&java.lang.CharSequence&java.lang.Runnable",
            "java.lang.CharSequence",
            "none",
            "java.lang.Runnable",
            "none");
  }

  @Test
  public void testScansRecursiveTypeVars() throws IOException {
    new Tester()
        .defineTypeParameter("T extends List<T>")
        .scanType("T")
        .assertScans("T", "<nulltype>", "java.util.List<T>", "none", "T");
  }

  @Test
  public void testScansWildcards() throws IOException {
    new Tester().scanType("List<?>").assertScans("java.util.List<?>", "none", "?");
  }

  @Test
  public void testScansExtendsWildcards() throws IOException {
    new Tester()
        .scanType("List<? extends Runnable>")
        .assertScans(
            "java.util.List<? extends java.lang.Runnable>",
            "none",
            "? extends java.lang.Runnable",
            "java.lang.Runnable",
            "none");
  }

  @Test
  public void testScansSuperWildcards() throws IOException {
    new Tester()
        .scanType("List<? super String>")
        .assertScans(
            "java.util.List<? super java.lang.String>",
            "none",
            "? super java.lang.String",
            "java.lang.String",
            "none");
  }

  private class Tester {
    private final List<String> typeParameters = new ArrayList<>();
    private List<String> scanResults;
    private String extraCode = "";

    public Tester defineTypeParameter(String typeParam) {
      typeParameters.add(typeParam);
      return this;
    }

    public Tester addExtraCode(String... lines) {
      extraCode = extraCode + String.join("\n", lines);
      return this;
    }

    public Tester scanType(String type) throws IOException {
      compiler.addSourceFileContents(
          "Foo.java",
          "import java.util.*;",
          String.format("public class Foo%s {", formatTypeParameters()),
          extraCode,
          String.format("  public %s toScan;", type),
          "}");
      compiler.setProcessors(Collections.emptyList());
      compiler.enter();

      Elements elements = compiler.getElements();
      TypeElement fooType = elements.getTypeElement("Foo");
      VariableElement toScan = ElementFilter.fieldsIn(fooType.getEnclosedElements()).get(0);

      TestTypeScanner scanner = new TestTypeScanner();
      scanner.scan(toScan.asType());
      scanResults = scanner.scans;
      return this;
    }

    public void assertScans(String... scans) {
      assertThat(scanResults, Matchers.contains(scans));
    }

    private String formatTypeParameters() {
      if (typeParameters.isEmpty()) {
        return "";
      }
      return String.format("<%s>", String.join(",", typeParameters));
    }
  }

  private static class TestTypeScanner extends TypeScanner8<Void, Void> {
    private final List<String> scans = new ArrayList<>();

    @Override
    public Void visitArray(ArrayType t, Void aVoid) {
      scans.add(t.toString());
      return super.visitArray(t, aVoid);
    }

    @Override
    public Void visitDeclared(DeclaredType t, Void aVoid) {
      scans.add(t.toString());
      return super.visitDeclared(t, aVoid);
    }

    @Override
    public Void visitTypeVariable(TypeVariable t, Void aVoid) {
      scans.add(t.toString());
      return super.visitTypeVariable(t, aVoid);
    }

    @Override
    public Void visitWildcard(WildcardType t, Void aVoid) {
      scans.add(t.toString());
      return super.visitWildcard(t, aVoid);
    }

    @Override
    public Void visitExecutable(ExecutableType t, Void aVoid) {
      scans.add(t.toString());
      return super.visitExecutable(t, aVoid);
    }

    @Override
    public Void visitIntersection(IntersectionType t, Void aVoid) {
      scans.add(t.toString());
      return super.visitIntersection(t, aVoid);
    }

    @Override
    public Void visitUnion(UnionType t, Void aVoid) {
      scans.add(t.toString());
      return super.visitUnion(t, aVoid);
    }

    @Override
    public Void visitPrimitive(PrimitiveType t, Void aVoid) {
      scans.add(t.toString());
      return super.visitPrimitive(t, aVoid);
    }

    @Override
    public Void visitNull(NullType t, Void aVoid) {
      scans.add(t.toString());
      return super.visitNull(t, aVoid);
    }

    @Override
    public Void visitError(ErrorType t, Void aVoid) {
      scans.add(t.toString());
      return super.visitError(t, aVoid);
    }

    @Override
    public Void visitNoType(NoType t, Void aVoid) {
      scans.add(t.toString());
      return super.visitNoType(t, aVoid);
    }
  }
}
