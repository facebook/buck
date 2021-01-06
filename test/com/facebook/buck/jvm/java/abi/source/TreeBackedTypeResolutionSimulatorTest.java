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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.abi.source.TreeBackedTypeResolutionSimulator.TreeBackedResolvedType;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiTestRunner.class)
public class TreeBackedTypeResolutionSimulatorTest extends CompilerTreeApiTest {

  @Override
  protected void initCompiler(Map<String, String> fileNamesToContents) throws IOException {
    super.initCompiler(fileNamesToContents);
  }

  @Test
  public void testNonCanonicalFullyQualifiedReferenceSuggestsCanonicalWhenAccessible()
      throws IOException {
    withClasspath(SimulatorTestSources.SUBCLASS);
    withClasspath(SimulatorTestSources.SUPERCLASS);
    withClasspath(SimulatorTestSources.GRAND_SUPERCLASS);
    withClasspath(SimulatorTestSources.INTERFACE1);
    withClasspath(SimulatorTestSources.GRAND_INTERFACE);
    withClasspath(SimulatorTestSources.INTERFACE2);

    compile(
        Joiner.on('\n')
            .join(
                "public class Foo {",
                "  com.facebook.subclass.Subclass.GrandSuperMember field;",
                "}"));

    assertResolutionResults(
        "com",
        null,
        "com.facebook",
        null,
        "com.facebook.subclass",
        "Source-only ABI generation requires that this type be referred to by its canonical name.\nTo fix: \nUse \"grandsuper\" here instead of \"subclass\".\n",
        "com.facebook.subclass.Subclass",
        "Source-only ABI generation requires that this type be referred to by its canonical name.\nTo fix: \nUse \"GrandSuper\" here instead of \"Subclass\".\n",
        "com.facebook.subclass.Subclass.GrandSuperMember",
        null);
  }

  @Test
  public void testNonCanonicalReferenceFailsWhenCanonicalInaccessible() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "public class Foo {",
                "  Bar.Inner.Innermost field;",
                "}",
                "class Bar {",
                "  protected static class Inner extends PrivateInner {",
                "  }",
                "  private static class PrivateInner {",
                "    protected static class Innermost { }",
                "  }",
                "}"));

    assertResolutionFails();
  }

  @Test
  public void testCanonicalReferenceWithBadClassCasing() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/example/buck/someclass.java",
            Joiner.on('\n').join("package com.example.buck;", "public class someclass { }")));

    compile(
        Joiner.on('\n')
            .join("public class Foo {", "  public com.example.buck.someclass field;", "}"));
    assertResolutionResults(
        "com",
        null,
        "com.example",
        null,
        "com.example.buck",
        null,
        "com.example.buck.someclass",
        "Source-only ABI generation requires top-level class names to start with a capital letter.\nTo fix: \nRename \"someclass\" to \"Someclass\".\n");
  }

  @Test
  public void testCanonicalReferenceWithBadMemberClassCasingSucceeds() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/example/buck/Someclass.java",
            Joiner.on('\n')
                .join(
                    "package com.example.buck;",
                    "public class Someclass { public class inner { } }")));

    compile(
        Joiner.on('\n')
            .join("public class Foo {", "  public com.example.buck.Someclass.inner field;", "}"));
    assertResolutionResults(
        "com",
        null,
        "com.example",
        null,
        "com.example.buck",
        null,
        "com.example.buck.Someclass",
        null,
        "com.example.buck.Someclass.inner",
        null);
  }

  @Test
  public void testCanonicalReferenceWithBadPackageCasing() throws IOException {
    withClasspath(
        ImmutableMap.of(
            "com/example/Buck/Someclass.java",
            Joiner.on('\n').join("package com.example.Buck;", "public class Someclass { }")));

    compile(
        Joiner.on('\n')
            .join("public class Foo {", "  public com.example.Buck.Someclass field;", "}"));
    assertResolutionResults(
        "com",
        null,
        "com.example",
        null,
        "com.example.Buck",
        "Source-only ABI generation requires package names to start with a lowercase letter.\nTo fix: \nRename \"Buck\" to \"buck\".\n",
        "com.example.Buck.Someclass",
        null);
  }

  @Test
  public void testStarImportSuggestsSingleTypeImport() throws IOException {
    withClasspath(SimulatorTestSources.GRAND_SUPERCLASS);

    compile(
        Joiner.on('\n')
            .join(
                "import com.facebook.grandsuper.*;",
                "public class Foo {",
                "  GrandSuper field;",
                "}"));

    assertResolutionResults(
        "GrandSuper",
        "Source-only ABI generation requires that this type be explicitly imported (star imports are not accepted).\nTo fix: \nAdd an import for \"com.facebook.grandsuper.GrandSuper\"\n");
  }

  @Test
  public void testCanonicalStaticStarImportSuggestsSingleTypeImport() throws IOException {
    withClasspath(SimulatorTestSources.GRAND_SUPERCLASS);

    compile(
        Joiner.on('\n')
            .join(
                "import static com.facebook.grandsuper.GrandSuper.*;",
                "public class Foo {",
                "  GrandSuperMember field;",
                "}"));

    assertResolutionResults(
        "GrandSuperMember",
        "Source-only ABI generation requires that this type be explicitly imported (star imports are not accepted).\nTo fix: \nAdd an import for \"com.facebook.grandsuper.GrandSuper.GrandSuperMember\"\n");
  }

  @Test
  public void testCanonicalStaticImportFails() throws IOException {
    withClasspath(SimulatorTestSources.GRAND_SUPERCLASS);

    compile(
        Joiner.on('\n')
            .join(
                "import static com.facebook.grandsuper.GrandSuper.GrandSuperMember;",
                "public class Foo {",
                "  GrandSuperMember field;",
                "}"));

    assertResolutionFails();
  }

  @Test
  public void testNonCanonicalStaticStarImportFails() throws IOException {
    withClasspath(SimulatorTestSources.SUPERCLASS);
    withClasspath(SimulatorTestSources.GRAND_SUPERCLASS);

    compile(
        Joiner.on('\n')
            .join(
                "import static com.facebook.superclass.Super.*;",
                "public class Foo {",
                "  GrandSuperMember field;",
                "}"));

    assertResolutionFails();
  }

  @Test
  public void testMemberImportSuggestsQualification() throws IOException {
    withClasspath(SimulatorTestSources.SUBCLASS);
    withClasspath(SimulatorTestSources.SUPERCLASS);
    withClasspath(SimulatorTestSources.GRAND_SUPERCLASS);
    withClasspath(SimulatorTestSources.INTERFACE1);
    withClasspath(SimulatorTestSources.GRAND_INTERFACE);
    withClasspath(SimulatorTestSources.INTERFACE2);

    compile(
        Joiner.on('\n')
            .join(
                "import com.facebook.subclass.Subclass;",
                "public class Foo extends Subclass{",
                "  GrandSuperMember field;",
                "}"));

    assertResolutionResults(
        "GrandSuperMember",
        "Source-only ABI generation requires that this member type reference be more explicit.\nTo fix: \nAdd an import for \"com.facebook.grandsuper.GrandSuper\"\nUse \"GrandSuper.GrandSuperMember\" here instead of \"GrandSuperMember\".\n");
  }

  @Test
  public void testNonCanonicalStaticImportFails() throws IOException {
    withClasspath(SimulatorTestSources.SUBCLASS);
    withClasspath(SimulatorTestSources.SUPERCLASS);
    withClasspath(SimulatorTestSources.GRAND_SUPERCLASS);
    withClasspath(SimulatorTestSources.INTERFACE1);
    withClasspath(SimulatorTestSources.GRAND_INTERFACE);
    withClasspath(SimulatorTestSources.INTERFACE2);

    compile(
        Joiner.on('\n')
            .join(
                "import static com.facebook.subclass.Subclass.GrandSuperMember;",
                "public class Foo {",
                "  GrandSuperMember field;",
                "}"));

    assertResolutionFails();
  }

  private void assertResolutionFails() {
    assertFalse(resolveFieldType().isCorrectable());
  }

  private void assertResolutionResults(String... expectedResults) {
    Preconditions.checkArgument(expectedResults.length % 2 == 0);

    List<String> actualResults = new ArrayList<>();
    TreeBackedResolvedType walker = resolveFieldType();
    while (walker != null) {
      actualResults.add(walker.getErrorMessage());
      actualResults.add(walker.location.getLeaf().toString());
      walker = walker.enclosingElement;
    }

    assertThat(Lists.reverse(actualResults), Matchers.contains(expectedResults));
  }

  private TreeBackedResolvedType resolveFieldType() {
    TypeElement fooElement = elements.getTypeElement("Foo");
    VariableElement field = ElementFilter.fieldsIn(fooElement.getEnclosedElements()).get(0);
    TreePath fieldTreePath = trees.getPath(field);
    VariableTree fieldTree = (VariableTree) fieldTreePath.getLeaf();
    TreePath typeTreePath = new TreePath(fieldTreePath, fieldTree.getType());

    CompilationUnitTree compilationUnit = typeTreePath.getCompilationUnit();
    TreeBackedTypeResolutionSimulator resolver =
        new TreeBackedTypeResolutionSimulator(elements, trees, compilationUnit);
    resolver.setImports(
        ImportsTrackerTestHelper.loadImports(elements, types, trees, compilationUnit));

    return resolver.resolve(typeTreePath);
  }
}
