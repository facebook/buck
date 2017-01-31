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

import com.facebook.buck.jvm.java.testutil.CompilerTreeApiParameterized;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.sun.source.tree.CompilationUnitTree;

import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import javax.lang.model.element.Element;

@RunWith(CompilerTreeApiParameterized.class)
public class FrontendOnlyJavacTaskTest extends CompilerTreeApiParameterizedTest {
  @Test
  public void testParseReturnsAllCompilationUnits() throws IOException {
    initCompiler(ImmutableMap.of(
        "Foo.java",
        "public class Foo { }",
        "Bar.java",
        "public class Bar { }",
        "Baz.java",
        "public class Baz { }"));

    Iterable<? extends CompilationUnitTree> compilationUnits = javacTask.parse();

    List<String> fileNames = RichStream.from(compilationUnits)
        .map(compilationUnit ->
            Paths.get(compilationUnit.getSourceFile().getName()).getFileName().toString())
        .toImmutableList();

    assertThat(fileNames, Matchers.contains("Foo.java", "Bar.java", "Baz.java"));
  }

  @Test
  public void testAnalyzeReturnsTopLevelTypesAndPackagesWithInfoFilesOnly() throws IOException {
    initCompiler(ImmutableMap.of(
        "Foo.java",
        Joiner.on('\n').join(
            "package com.facebook.foo;",
            "public class Foo {",
            "  private Runnable field = new Runnable() {",
            "    public void run() { }",
            "  };",
            "  private void method() {",
            "    class Local { };",
            "  }",
            "  private class Inner { }",
            "}",
            "class Extra { }"),
        "Bar.java",
        Joiner.on('\n').join(
            "package com.facebook.bar;",
            "class Bar { }"),
        "package-info.java",
        "package com.facebook.foo;"
    ));

    Iterable<? extends Element> actualElements = javacTask.analyze();

    assertThat(actualElements, Matchers.containsInAnyOrder(
        elements.getPackageElement("com.facebook.foo"),
        elements.getTypeElement("com.facebook.foo.Foo"),
        elements.getTypeElement("com.facebook.foo.Extra"),
        elements.getTypeElement("com.facebook.bar.Bar")));
  }
}
