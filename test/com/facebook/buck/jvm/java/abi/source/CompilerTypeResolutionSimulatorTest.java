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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiParameterized;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import com.google.common.base.Joiner;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.util.Map;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(CompilerTreeApiParameterized.class)
public class CompilerTypeResolutionSimulatorTest extends CompilerTreeApiTest {
  @Parameters(name = "{0}")
  public static Object[] getParameters() {
    return ResolvedTypeKind.values();
  }

  @Parameter public ResolvedTypeKind kind;

  private TestSourceOnlyAbiRuleInfo ruleInfo = new TestSourceOnlyAbiRuleInfo("//:test");
  private CompilerTypeResolutionSimulator resolver;

  @Before
  public void setUp() throws IOException {
    withClasspath(SimulatorTestSources.SUBCLASS);
    withClasspath(SimulatorTestSources.SUPERCLASS);
    withClasspath(SimulatorTestSources.GRAND_SUPERCLASS);
    withClasspath(SimulatorTestSources.INTERFACE1);
    withClasspath(SimulatorTestSources.GRAND_INTERFACE);
    withClasspath(SimulatorTestSources.INTERFACE2);

    ruleInfo.addElementOwner("com.facebook.subclass.Subclass", "//com/facebook/subclass:subclass");
    ruleInfo.addElementOwner(
        "com.facebook.superclass.Super", "//com/facebook/superclass:superclass");
    ruleInfo.addElementOwner(
        "com.facebook.grandsuper.GrandSuper", "//com/facebook/grandsuper:grandsuper");
    ruleInfo.addElementOwner("com.facebook.iface1.Interface1", "//com/facebook/iface1:iface1");
    ruleInfo.addElementOwner(
        "com.facebook.grandinterface.GrandInterface",
        "//com/facebook/grandinterface:grandinterface");
    ruleInfo.addElementOwner("com.facebook.iface2.Interface2", "//com/facebook/iface2:iface2");
    ruleInfo.addElementOwner("com.facebook.subclass.Subclass", "//com/facebook/subclass:subclass");

    ruleInfo.addAvailableRule("//com/facebook/superclass:superclass");
    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/iface2:iface2");
    ruleInfo.addAvailableRule("//com/facebook/grandinterface:grandinterface");

    switch (kind) {
      case RESOLVED_TYPE:
        ruleInfo.addAvailableRule("//com/facebook/subclass:subclass");
        ruleInfo.addAvailableRule("//com/facebook/grandsuper:grandsuper");
        break;
      case ERROR_TYPE:
        break;
      case CRASH:
        ruleInfo.addAvailableRule("//com/facebook/subclass:subclass");
        break;
    }
  }

  @Override
  protected void initCompiler(Map<String, String> fileNamesToContents) throws IOException {
    super.initCompiler(fileNamesToContents);

    FileManagerSimulator fileManager = new FileManagerSimulator(elements, trees, ruleInfo);
    resolver = new CompilerTypeResolutionSimulator(trees, fileManager);
  }

  @Test
  public void testResolvingTypeFromDependencies() throws IOException {
    compile(
        Joiner.on('\n')
            .join(
                "public class Foo {",
                "  com.facebook.subclass.Subclass.GrandSuperMember field;",
                "}"));

    ResolvedType result = resolveField();

    assertSame(
        elements.getTypeElement("com.facebook.grandsuper.GrandSuper.GrandSuperMember"),
        result.type);
    assertEquals(kind, result.kind);

    if (kind == ResolvedTypeKind.ERROR_TYPE) {
      assertThat(
          result.missingDependencies,
          Matchers.containsInAnyOrder(
              "//com/facebook/subclass:subclass", "//com/facebook/grandsuper:grandsuper"));
    } else if (kind == ResolvedTypeKind.CRASH) {
      assertThat(
          result.missingDependencies,
          Matchers.containsInAnyOrder("//com/facebook/grandsuper:grandsuper"));
    }
  }

  @Test
  public void testResolvingTypeViaLocalSubclass() throws IOException {
    if (kind == ResolvedTypeKind.ERROR_TYPE) {
      // It isn't actually possible to get an error type in this situation, because once you've
      // started traversing enclosing classes, you've got to be able to traverse them all.
      return;
    }

    compile(
        Joiner.on('\n')
            .join(
                "public class Foo {",
                "  Other.GrandSuperMember field;",
                "}",
                "class Other extends com.facebook.subclass.Subclass {",
                "}"));

    ResolvedType result = resolveField();

    assertSame(
        elements.getTypeElement("com.facebook.grandsuper.GrandSuper.GrandSuperMember"),
        result.type);
    assertEquals(kind, result.kind);

    if (kind == ResolvedTypeKind.ERROR_TYPE) {
      assertThat(
          result.missingDependencies,
          Matchers.containsInAnyOrder(
              "//com/facebook/subclass:subclass", "//com/facebook/grandsuper:grandsuper"));
    } else if (kind == ResolvedTypeKind.CRASH) {
      assertThat(
          result.missingDependencies,
          Matchers.containsInAnyOrder("//com/facebook/grandsuper:grandsuper"));
    }
  }

  private ResolvedType resolveField() {
    TypeElement fooType = elements.getTypeElement("Foo");
    VariableElement field = ElementFilter.fieldsIn(fooType.getEnclosedElements()).get(0);
    TreePath fieldTreePath = trees.getPath(field);
    VariableTree fieldTree = (VariableTree) fieldTreePath.getLeaf();
    TreePath fieldTypePath = new TreePath(fieldTreePath, fieldTree.getType());

    return resolver.resolve(fieldTypePath);
  }
}
