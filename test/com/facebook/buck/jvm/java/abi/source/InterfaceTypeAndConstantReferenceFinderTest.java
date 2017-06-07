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

import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.plugin.adapter.BuckJavacTask;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiTestRunner.class)
public class InterfaceTypeAndConstantReferenceFinderTest extends CompilerTreeApiTest {
  private List<String> typeReferences;
  private List<String> constantReferences;
  private List<TypeElement> importedTypes;

  @Test
  public void testFindsVariableTypeReference() throws IOException {
    findTypeReferences("class Foo {", "  String s;", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.String", 2, 3)));
  }

  @Test
  public void testFindsTypeReferencesInNestedTypes() throws IOException {
    findTypeReferences("class Foo {", "  class Bar {", "    String s;", "  }", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.String", 3, 5)));
  }

  @Test
  public void testIgnoresPrivateNestedTypes() throws IOException {
    findTypeReferences("class Foo {", "  private class Bar {", "    String s;", "  }", "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testFindsTypeArgTypeReference() throws IOException {
    findTypeReferences("import java.util.Map;", "class Foo {", "  Map<String, Integer> s;", "}");

    assertThat(
        typeReferences,
        Matchers.contains(
            createSymbolicReference("java.util.Map", 3, 3),
            createSymbolicReference("java.lang.String", 3, 7),
            createSymbolicReference("java.lang.Integer", 3, 15)));
  }

  @Test
  public void testFindsWildcardBoundsTypeReference() throws IOException {
    findTypeReferences(
        "import java.util.Map;",
        "class Foo {",
        "  Map<? extends CharSequence, ? super Integer> s;",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.util.Map", 3, 3),
            createSymbolicReference("java.lang.CharSequence", 3, 17),
            createSymbolicReference("java.lang.Integer", 3, 39)));
  }

  @Test
  public void testFindsArrayElementTypeReference() throws IOException {
    findTypeReferences("class Foo {", "  String[][] s;", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.String", 2, 3)));
  }

  @Test
  public void testFindsFullyQualifiedTypeReference() throws IOException {
    findTypeReferences("class Foo {", "  java.lang.String s;", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.String", 2, 3)));
  }

  @Test
  public void testFindsTypeParameterBoundTypeReferences() throws IOException {
    findTypeReferences("class Foo<T extends Runnable & CharSequence> { }");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.Runnable", 1, 21),
            createSymbolicReference("java.lang.CharSequence", 1, 32)));
  }

  @Test
  public void testIgnoresTypeVariableTypeReferences() throws IOException {
    findTypeReferences("class Foo<T extends Runnable & CharSequence> {", "  T t;", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.Runnable", 1, 21),
            createSymbolicReference("java.lang.CharSequence", 1, 32)));
  }

  @Test
  public void testIgnoresPrimitiveTypeReferences() throws IOException {
    findTypeReferences("class Foo {", "  int i;", "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testIgnoresNullTypeReferences() throws IOException {
    findTypeReferences("class Foo<T> { }");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testIgnoresVoidTypeReferences() throws IOException {
    findTypeReferences("class Foo {", "  void method() {  };", "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testFindsReturnTypeReference() throws IOException {
    findTypeReferences("class Foo {", "  String method() { return null; }", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.String", 2, 3)));
  }

  @Test
  public void testFindsMethodParameterTypeReferences() throws IOException {
    findTypeReferences("class Foo {", "  void method(String s, Integer i) { }", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.String", 2, 15),
            createSymbolicReference("java.lang.Integer", 2, 25)));
  }

  @Test
  public void testFindsMethodTypeParameterTypeReferences() throws IOException {
    findTypeReferences("class Foo {", "  <T extends CharSequence> void method() { }", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.CharSequence", 2, 14)));
  }

  @Test
  public void testFindsSuperclassTypeReferences() throws IOException {
    findTypeReferences("abstract class Foo extends java.util.ArrayList { } ");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.util.ArrayList", 1, 28)));
  }

  @Test
  public void testFindsInterfaceTypeReferences() throws IOException {
    findTypeReferences("abstract class Foo implements Runnable, CharSequence { } ");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.Runnable", 1, 31),
            createSymbolicReference("java.lang.CharSequence", 1, 41)));
  }

  @Test
  public void testFindsAnnotationTypeReferencesOnMethods() throws IOException {
    findTypeReferences(
        "class Foo implements Runnable {", "  @Override", "  public void run() { }", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.Runnable", 1, 22),
            createSymbolicReference("java.lang.Override", 2, 4)));
  }

  @Test
  public void testFindsAnnotationTypeReferencesOnFields() throws IOException {
    findTypeReferences("class Foo {", "  @SuppressWarnings(\"foo\")", "  String s;", "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.SuppressWarnings", 2, 4),
            createSymbolicReference("java.lang.String", 3, 3)));
  }

  @Test
  public void testFindsAnnotationTypeReferencesOnTypeParameters() throws IOException {
    findTypeReferences(
        "import java.lang.annotation.*;",
        "class Foo<@Anno T> { }",
        "@Target(ElementType.TYPE_PARAMETER)",
        "@interface Anno { }");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("Anno", 2, 12),
            createSymbolicReference("java.lang.annotation.Target", 3, 2),
            createSymbolicReference("java.lang.annotation.ElementType", 3, 9)));
  }

  @Test
  public void testFindsAnnotationTypeReferencesOnTypes() throws IOException {
    findTypeReferences(
        "import java.lang.annotation.*;",
        "abstract class Foo implements @Anno CharSequence { }",
        "@Target(ElementType.TYPE_USE)",
        "@interface Anno { }");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("Anno", 2, 32),
            createSymbolicReference("java.lang.CharSequence", 2, 37),
            createSymbolicReference("java.lang.annotation.Target", 3, 2),
            createSymbolicReference("java.lang.annotation.ElementType", 3, 9)));
  }

  @Test
  public void testFindsAnnotationTypeReferencesOnClasses() throws IOException {
    findTypeReferences("import java.lang.annotation.*;", "@Documented", "@interface Foo { }");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.annotation.Documented", 2, 2)));
  }

  @Test
  public void testIgnoresMethodBodies() throws IOException {
    findTypeReferences("class Foo {", "  void method() {", "    String s;", "  }", "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testIgnoresAnonymousClasses() throws IOException {
    findTypeReferences(
        "class Foo {",
        "  Runnable r = new Runnable() {",
        "    String s;",
        "    public void run() { }",
        "  };",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(createSymbolicReference("java.lang.Runnable", 2, 3)));
  }

  @Test
  public void testIgnoresPrivateMethods() throws IOException {
    findTypeReferences("class Foo {", "  private String method() { return null; }", "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testIgnoresPrivateFields() throws IOException {
    findTypeReferences("class Foo {", "  private String s;", "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testIgnoresPrivateTypes() throws IOException {
    findTypeReferences("class Foo {", "  private class Bar {", "    public String s;", "  }", "}");

    assertThat(typeReferences, Matchers.empty());
  }

  @Test
  public void testFindsReferencesInConstants() throws IOException {
    findTypeReferences(
        "class Foo {",
        "  public static final String s = Constants.CONSTANT;",
        "}",
        "class Constants {",
        "  public static final String CONSTANT = \"Hello\";",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.String", 2, 23),
            createSymbolicReference("Constants", 2, 34),
            createSymbolicReference("java.lang.String", 5, 23)));
  }

  @Test
  public void testIgnoresReferencesToOtherConstantMembers() throws IOException {
    findTypeReferences(
        "class Foo {",
        "  public static final String CONSTANT = \"Hello\";",
        "  public static final String s = CONSTANT;",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.String", 3, 23),
            createSymbolicReference("java.lang.String", 2, 23)));
  }

  @Test
  public void testIgnoresReferencesInNonStaticConstants() throws IOException {
    findTypeReferences(
        "class Foo {",
        "  public final String s = Constants.CONSTANT;",
        "}",
        "class Constants {",
        "  public static final String CONSTANT = \"Hello\";",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.String", 2, 16),
            createSymbolicReference("java.lang.String", 5, 23)));
  }

  @Test
  public void testFindsReferencesInMethodDefaultValues() throws IOException {
    findTypeReferences(
        "@interface Foo {",
        "  String value() default Constants.CONSTANT;",
        "}",
        "class Constants {",
        "  public static final String CONSTANT = \"Hello\";",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.String", 2, 3),
            createSymbolicReference("Constants", 2, 26),
            createSymbolicReference("java.lang.String", 5, 23)));
  }

  @Test
  public void testFindsReferencesInAnnotationValues() throws IOException {
    findTypeReferences(
        "@SuppressWarnings(Constants.CONSTANT)",
        "class Foo {",
        "}",
        "class Constants {",
        "  public static final String CONSTANT = \"Hello\";",
        "}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("java.lang.SuppressWarnings", 1, 2),
            createSymbolicReference("Constants", 1, 19),
            createSymbolicReference("java.lang.String", 5, 23)));
  }

  @Test
  public void testFindsReferencesInAnnotationValuesThatAreAnnotations() throws IOException {
    findTypeReferences(
        "@Bar(@Baz)",
        "class Foo {",
        "}",
        "@interface Bar {",
        "  Baz value();",
        "}",
        "@interface Baz {}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("Bar", 1, 2),
            createSymbolicReference("Baz", 1, 7),
            createSymbolicReference("Baz", 5, 3)));
  }

  @Test
  public void testFindsReferencesInAnnotationValuesThatAreAnnotationArrays() throws IOException {
    findTypeReferences(
        "@Bar({@Baz, @Baz})",
        "class Foo {",
        "}",
        "@interface Bar {",
        "  Baz[] value();",
        "}",
        "@interface Baz {}");

    assertThat(
        typeReferences,
        Matchers.containsInAnyOrder(
            createSymbolicReference("Bar", 1, 2),
            createSymbolicReference("Baz", 1, 8),
            createSymbolicReference("Baz", 1, 14),
            createSymbolicReference("Baz", 5, 3)));
  }

  @Test
  public void testFindsImportedTypes() throws IOException {
    findTypeReferences("import java.util.List;", "class Foo { }");

    assertThat(
        importedTypes, Matchers.containsInAnyOrder(elements.getTypeElement("java.util.List")));
  }

  @Test
  public void testIgnoresStaticImports() throws IOException {
    findTypeReferences("import static java.util.Collections.*;", "class Foo { }");

    assertThat(importedTypes, Matchers.empty());
  }

  @Test
  public void testDoesNotCrashOnSimpleNamedPackage() throws IOException {
    findTypeReferencesErrorsOK("package example;", "class Foo { }");
  }

  @Test
  public void testFindsSimpleNameConstants() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/foo/Constants.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.foo;",
                    "class Constants {",
                    "  protected static final int CONSTANT = 3;",
                    "}")));
    findTypeReferences(
        "package com.facebook.foo;",
        "class Foo extends Constants {",
        "  public static final int CONSTANT2 = CONSTANT;",
        "}");

    assertThat(
        constantReferences, Matchers.containsInAnyOrder(createSymbolicReference("3", 3, 39)));
  }

  @Test
  public void testFindsQualifiedNameConstants() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/foo/Constants.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.foo;",
                    "public class Constants {",
                    "  public static final int CONSTANT = 3;",
                    "}")));
    findTypeReferences(
        "class Foo {",
        "  public static final int CONSTANT2 = com.facebook.foo.Constants.CONSTANT;",
        "}");

    assertThat(
        constantReferences, Matchers.containsInAnyOrder(createSymbolicReference("3", 2, 39)));
  }

  @Test
  public void testIgnoresReferencesToNonexistentTypes() throws IOException {
    findTypeReferencesErrorsOK(
        "package com.facebook.foo;",
        "class Foo {",
        "  protected Bar getBar() { return null; };",
        "}");

    assertThat(typeReferences, Matchers.empty());
  }

  private void findTypeReferences(String... sourceLines) throws IOException {
    findTypeReferences(sourceLines, false);
  }

  private void findTypeReferencesErrorsOK(String... sourceLines) throws IOException {
    findTypeReferences(sourceLines, true);
  }

  private void findTypeReferences(String[] sourceLines, boolean errorsOK) throws IOException {
    constantReferences = new ArrayList<>();
    typeReferences = new ArrayList<>();
    importedTypes = new ArrayList<>();

    compile(
        ImmutableMap.of("Foo.java", Joiner.on('\n').join(sourceLines)),
        // ErrorType's get nulled out when the task returns, so we have to get a call back during
        // the run so that we can still look at them.
        new TaskListenerFactory() {
          @Override
          public TaskListener newTaskListener(BuckJavacTask task) {
            return new PostEnterCallback() {
              @Override
              protected void enterComplete(List<CompilationUnitTree> compilationUnits) {
                InterfaceTypeAndConstantReferenceFinder finder =
                    new InterfaceTypeAndConstantReferenceFinder(trees, new FinderListener());
                finder.findReferences(compilationUnits);
              }
            };
          }
        });

    if (!errorsOK) {
      assertNoErrors();
    }
  }

  private String createSymbolicReference(String referent, TreePath treePath) {
    try {
      long position =
          trees
              .getSourcePositions()
              .getStartPosition(treePath.getCompilationUnit(), treePath.getLeaf());

      CharSequence content = treePath.getCompilationUnit().getSourceFile().getCharContent(true);

      int line = 1;
      int column = 1;
      for (long i = 0; i < position; i++, column++) {
        if (content.charAt((int) i) == '\n') {
          line += 1;
          column = 0;
        }
      }

      return createSymbolicReference(referent, line, column);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private String createSymbolicReference(String referent, int line, int column) {
    return String.format("%d, %d: %s", line, column, referent);
  }

  private class FinderListener implements InterfaceTypeAndConstantReferenceFinder.Listener {
    @Override
    public void onTypeImported(TypeElement type) {
      importedTypes.add(type);
    }

    @Override
    public void onTypeReferenceFound(TypeElement type, TreePath path, Element enclosingElement) {
      typeReferences.add(createSymbolicReference(type.getQualifiedName().toString(), path));
    }

    @Override
    public void onConstantReferenceFound(
        VariableElement constant, TreePath path, Element enclosingElement) {
      constantReferences.add(createSymbolicReference(constant.getConstantValue().toString(), path));
    }
  }
}
