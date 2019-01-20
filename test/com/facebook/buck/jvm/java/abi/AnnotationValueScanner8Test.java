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

import com.facebook.buck.jvm.java.lang.model.AnnotationValueScanner8;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.facebook.buck.jvm.java.testutil.compiler.TestCompiler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiTestRunner.class)
public class AnnotationValueScanner8Test {
  @Rule public TestCompiler compiler = new TestCompiler();

  @Test
  public void testScansNestedArrays() throws IOException {
    compiler.addSourceFileContents(
        "Anno.java",
        "public @interface Anno {",
        "  String[][] doubleStringArray() default { { \"a\", \"b\" }, { \"c\", \"d\" } };",
        "}");
    compiler.setProcessors(Collections.emptyList());
    compiler.enter();
    assertThat(compiler.getDiagnosticMessages(), Matchers.empty());

    Elements elements = compiler.getElements();
    ExecutableElement annoParam =
        ElementFilter.methodsIn(elements.getTypeElement("Anno").getEnclosedElements()).get(0);

    TestAnnotationValueScanner scanner = new TestAnnotationValueScanner();
    scanner.scan(annoParam.getDefaultValue());

    assertThat(
        scanner.scans,
        Matchers.contains(
            "{{\"a\", \"b\"}, {\"c\", \"d\"}}",
            "{\"a\", \"b\"}",
            "a",
            "b",
            "{\"c\", \"d\"}",
            "c",
            "d"));
  }

  // TODO: Create a tester class that lets us create an annotation and a value thereof, then
  // scan it.

  private static class TestAnnotationValueScanner extends AnnotationValueScanner8<Void, Void> {
    private final List<String> scans = new ArrayList<>();

    @Override
    public Void visitBoolean(boolean b, Void aVoid) {
      scans.add(String.valueOf(b));
      return super.visitBoolean(b, aVoid);
    }

    @Override
    public Void visitByte(byte b, Void aVoid) {
      scans.add(String.valueOf(b));
      return super.visitByte(b, aVoid);
    }

    @Override
    public Void visitChar(char c, Void aVoid) {
      scans.add(String.valueOf(c));
      return super.visitChar(c, aVoid);
    }

    @Override
    public Void visitDouble(double d, Void aVoid) {
      scans.add(String.valueOf(d));
      return super.visitDouble(d, aVoid);
    }

    @Override
    public Void visitFloat(float f, Void aVoid) {
      scans.add(String.valueOf(f));
      return super.visitFloat(f, aVoid);
    }

    @Override
    public Void visitInt(int i, Void aVoid) {
      scans.add(String.valueOf(i));
      return super.visitInt(i, aVoid);
    }

    @Override
    public Void visitLong(long i, Void aVoid) {
      scans.add(String.valueOf(i));
      return super.visitLong(i, aVoid);
    }

    @Override
    public Void visitShort(short s, Void aVoid) {
      scans.add(String.valueOf(s));
      return super.visitShort(s, aVoid);
    }

    @Override
    public Void visitString(String s, Void aVoid) {
      scans.add(s);
      return super.visitString(s, aVoid);
    }

    @Override
    public Void visitType(TypeMirror t, Void aVoid) {
      scans.add(t.toString());
      return super.visitType(t, aVoid);
    }

    @Override
    public Void visitEnumConstant(VariableElement c, Void aVoid) {
      scans.add(c.toString());
      return super.visitEnumConstant(c, aVoid);
    }

    @Override
    public Void visitAnnotation(AnnotationMirror a, Void aVoid) {
      scans.add(a.toString());
      return super.visitAnnotation(a, aVoid);
    }

    @Override
    public Void visitArray(List<? extends AnnotationValue> vals, Void aVoid) {
      scans.add(
          String.format(
              "{%s}",
              vals.stream().map(AnnotationValue::toString).collect(Collectors.joining(", "))));
      return super.visitArray(vals, aVoid);
    }

    @Override
    public Void visitUnknown(AnnotationValue av, Void aVoid) {
      scans.add(av.toString());
      return super.visitUnknown(av, aVoid);
    }
  }
}
