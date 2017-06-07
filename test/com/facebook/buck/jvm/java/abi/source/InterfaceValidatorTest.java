/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.sun.source.tree.CompilationUnitTree;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiTestRunner.class)
public class InterfaceValidatorTest extends CompilerTreeApiTest {
  @Test
  public void testSimpleClassPasses() throws IOException {
    compileWithValidation("public class Foo { }");

    assertNoErrors();
  }

  @Test
  public void testImplicitAnnotationSuperclassSucceeds() throws IOException {
    compileWithValidation("@interface Foo { };");

    assertNoErrors();
  }

  @Test
  public void testFullyQualifiedNameFromBootClasspathSucceeds() throws IOException {
    compileWithValidation("abstract class Foo implements java.util.List { }");

    assertNoErrors();
  }

  @Test
  public void testFullyQualifiedNameFromClasspathSucceeds() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/bar/Bar.java",
            Joiner.on('\n').join("package com.facebook.bar;", "public class Bar { }")));
    compileWithValidation("class Foo extends com.facebook.bar.Bar { }");

    assertNoErrors();
  }

  @Test
  public void testSelfSucceeds() throws IOException {
    compileWithValidation(Joiner.on('\n').join("class Foo {", "  Foo f;", "}"));

    assertNoErrors();
  }

  @Test
  public void testSiblingTypeSucceeds() throws IOException {
    compileWithValidation(
        Joiner.on('\n')
            .join("class Foo {", "  class Bar {", "    Baz b;", "  }", "  class Baz { }", "}"));

    assertNoErrors();
  }

  @Test
  public void testSiblingOfParentTypeSucceeds() throws IOException {
    compileWithValidation(
        Joiner.on('\n')
            .join(
                "class Foo {",
                "  class Bar {",
                "    class Baz {",
                "      Waz w;",
                "    }",
                "  }",
                "  class Waz { }",
                "}"));

    assertNoErrors();
  }

  @Test
  public void testPackageReferenceFromInnerTypeSucceeds() throws IOException {
    compileWithValidation(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.foo;",
                    "class Foo {",
                    "  class Inner {",
                    "    Bar b;",
                    "  }",
                    "}"),
            "Bar.java",
            Joiner.on('\n').join("package com.facebook.foo;", "class Bar { }")));

    assertNoErrors();
  }

  @Test
  public void testStarImportedTypeFromClasspathFails() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/bar/Bar.java",
            Joiner.on('\n').join("package com.facebook.bar;", "public class Bar { }")));
    compileWithValidation(
        Joiner.on('\n')
            .join("import com.facebook.bar.*;", "public abstract class Foo extends Bar { }"));

    assertErrors(
        Joiner.on('\n')
            .join(
                "Foo.java:2: error: Must qualify the name: com.facebook.bar.Bar",
                "public abstract class Foo extends Bar { }",
                "                                  ^"));
  }

  @Test
  public void testStarImportedTypeCompiledTogetherSucceeds() throws IOException {
    compileWithValidation(
        ImmutableMap.of(
            "com/facebook/bar/Bar.java",
            Joiner.on('\n').join("package com.facebook.bar;", "public class Bar { }"),
            "Foo.java",
            Joiner.on('\n')
                .join("import com.facebook.bar.*;", "public abstract class Foo extends Bar { }")));

    assertNoErrors();
  }

  @Test
  public void testStarImportedTypeFromBootClasspathFails() throws IOException {
    this.compileWithValidation(
        Joiner.on('\n')
            .join("import java.util.*;", "public abstract class Foo implements List<String> { }"));

    assertNoErrors();
  }

  @Test
  public void testOwnMemberTypeSucceeds() throws IOException {
    compileWithValidation(
        Joiner.on('\n')
            .join(
                "package com.facebook.foo;",
                "public class Foo {",
                "  Inner i;",
                "  class Inner { }",
                "}"));

    assertNoErrors();
  }

  @Test
  public void testSuperclassMemberTypeFromClasspathFails() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/bar/Bar.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.bar;",
                    "import com.facebook.foo.Baz;",
                    "public class Bar implements Baz { }"),
            "com/facebook/foo/Baz.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.foo;", "public interface Baz { interface Inner { } }")));
    compileWithValidation(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.foo;",
                    "import com.facebook.bar.Bar;",
                    "class Foo extends Bar {",
                    "  Inner i;",
                    "}")));

    assertError(
        Joiner.on('\n')
            .join("Foo.java:4: error: Must qualify the name: Baz.Inner", "  Inner i;", "  ^"));
  }

  @Test
  public void testImportedSuperclassMemberTypeFromClasspathSucceeds() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/bar/Bar.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.bar;",
                    "import com.facebook.foo.Baz;",
                    "public class Bar implements Baz { }"),
            "com/facebook/foo/Baz.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.foo;", "public interface Baz { interface Inner { } }")));
    compileWithValidation(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n')
                .join(
                    "package com.facebook.foo;",
                    "import com.facebook.bar.Bar;",
                    "import com.facebook.foo.Baz.Inner;",
                    "class Foo extends Bar {",
                    "  Inner i;",
                    "}")));

    assertNoErrors();
  }

  @Test
  public void testPackageMemberTypeCompiledTogetherSucceeds() throws IOException {
    compileWithValidation(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n').join("package com.facebook.foo;", "class Foo extends Bar { }"),
            "Bar.java",
            Joiner.on('\n').join("package com.facebook.foo;", "class Bar { }")));

    assertNoErrors();
  }

  @Test
  public void testPackageMemberTypeFromClasspathSucceeds() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/foo/Bar.java",
            Joiner.on('\n').join("package com.facebook.foo;", "class Bar { }")));
    compileWithValidation(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n').join("package com.facebook.foo;", "class Foo extends Bar { }")));

    assertNoErrors();
  }

  @Test
  public void testQualifiedPackageMemberInnerTypeFromClasspathSucceeds() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/foo/Bar.java",
            Joiner.on('\n')
                .join("package com.facebook.foo;", "class Bar { static class Inner { } }")));
    compileWithValidation(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n').join("package com.facebook.foo;", "class Foo extends Bar.Inner { }")));

    assertNoErrors();
  }

  @Test
  public void testUnqualifiedPackageMemberInnerTypeFromClasspathFails() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/facebook/foo/Bar.java",
            Joiner.on('\n')
                .join("package com.facebook.foo;", "class Bar { static class Inner { } }")));
    compileWithValidation(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n')
                .join("package com.facebook.foo;", "class Foo extends Bar {", "  Inner i;", "}")));

    assertError(
        Joiner.on('\n')
            .join("Foo.java:3: error: Must qualify the name: Bar.Inner", "  Inner i;", "  ^"));
  }

  @Test
  public void testConstantCompiledTogetherSucceeds() throws IOException {
    compileWithValidation(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n')
                .join(
                    "class Foo {",
                    "  public static final int CONSTANT = Constants.CONSTANT + 1 + Constants.CONSTANT2;",
                    "}"),
            "Constants.java",
            Joiner.on('\n')
                .join(
                    "class Constants {",
                    "  public static final int CONSTANT = 3 + 5;",
                    "  public static final int CONSTANT2 = 3;",
                    "}")));

    assertNoErrors();
  }

  @Test
  public void testConstantFromClasspathFails() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "Constants.java",
            Joiner.on('\n')
                .join(
                    "class Constants {",
                    "  public static final int CONSTANT = 3 + 5;",
                    "  public static final int CONST2 = 3;",
                    "}")));
    compileWithValidation(
        ImmutableMap.of(
            "Foo.java",
            Joiner.on('\n')
                .join(
                    "class Foo {",
                    "  public static final int CONSTANT = Constants.CONSTANT + 1 + Constants.CONST2;",
                    "}")));

    assertErrors(
        Joiner.on('\n')
            .join(
                "Foo.java:2: error: Must inline the constant value: 8",
                "  public static final int CONSTANT = Constants.CONSTANT + 1 + Constants.CONST2;",
                "                                              ^"),
        Joiner.on('\n')
            .join(
                "Foo.java:2: error: Must inline the constant value: 3",
                "  public static final int CONSTANT = Constants.CONSTANT + 1 + Constants.CONST2;",
                "                                                                       ^"));
  }

  protected Iterable<? extends CompilationUnitTree> compileWithValidation(String source)
      throws IOException {
    return compileWithValidation(ImmutableMap.of("Foo.java", source));
  }

  protected Iterable<? extends CompilationUnitTree> compileWithValidation(
      Map<String, String> sources) throws IOException {
    return compile(
        sources,
        // A side effect of our hacky test class loader appears to be that this only works if
        // it's NOT a lambda. LoL.
        new ValidatingTaskListenerFactory());
  }
}
