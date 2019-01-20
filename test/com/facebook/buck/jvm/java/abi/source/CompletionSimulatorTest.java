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

import com.facebook.buck.jvm.java.abi.source.CompletionSimulator.CompletedType;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTestRunner;
import java.io.IOException;
import java.util.Map;
import javax.lang.model.element.TypeElement;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(CompilerTreeApiTestRunner.class)
public class CompletionSimulatorTest extends CompilerTreeApiTest {
  private TestSourceOnlyAbiRuleInfo ruleInfo = new TestSourceOnlyAbiRuleInfo("//:test");
  private CompletionSimulator completer;

  @Before
  public void setUp() throws IOException {
    withClasspath(SimulatorTestSources.SUPERCLASS);
    withClasspath(SimulatorTestSources.GRAND_SUPERCLASS);
    withClasspath(SimulatorTestSources.INTERFACE1);
    withClasspath(SimulatorTestSources.GRAND_INTERFACE);
    withClasspath(SimulatorTestSources.INTERFACE2);

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
  }

  @Override
  protected void initCompiler(Map<String, String> fileNamesToContents) throws IOException {
    super.initCompiler(fileNamesToContents);

    FileManagerSimulator fileManager = new FileManagerSimulator(elements, trees, ruleInfo);
    completer = new CompletionSimulator(fileManager);
  }

  @Test
  public void testTransitiveCompletionWithAllSupersPresentCompletesSuccessfully()
      throws IOException {
    compile(SimulatorTestSources.SUBCLASS);

    ruleInfo.addAvailableRule("//com/facebook/superclass:superclass");
    ruleInfo.addAvailableRule("//com/facebook/grandsuper:grandsuper");
    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/iface2:iface2");
    ruleInfo.addAvailableRule("//com/facebook/grandinterface:grandinterface");

    CompletedType result = completeSubclass(true);

    assertThat(result.getMissingDependencies(), Matchers.empty());
    assertEquals(CompletedTypeKind.COMPLETED_TYPE, result.kind);
  }

  @Test
  public void testTransitiveMemberClassCompletionWithAllSupersPresentCompletesSuccessfully()
      throws IOException {
    compile(SimulatorTestSources.SUBCLASS);

    ruleInfo.addAvailableRule("//com/facebook/superclass:superclass");
    ruleInfo.addAvailableRule("//com/facebook/grandsuper:grandsuper");
    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/iface2:iface2");
    ruleInfo.addAvailableRule("//com/facebook/grandinterface:grandinterface");

    CompletedType result = completeSubclassMember(true);

    assertThat(result.getMissingDependencies(), Matchers.empty());
    assertEquals(CompletedTypeKind.COMPLETED_TYPE, result.kind);
  }

  @Test
  public void testTransitiveCompletionWithSomeSupersMissingCompletesPartially() throws IOException {
    compile(SimulatorTestSources.SUBCLASS);

    ruleInfo.addAvailableRule("//com/facebook/grandsuper:grandsuper");
    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/iface2:iface2");
    ruleInfo.addAvailableRule("//com/facebook/grandinterface:grandinterface");

    CompletedType result = completeSubclass(true);

    assertThat(
        result.getMissingDependencies(),
        Matchers.containsInAnyOrder("//com/facebook/superclass:superclass"));
    assertEquals(CompletedTypeKind.PARTIALLY_COMPLETED_TYPE, result.kind);
  }

  @Test
  public void testNonTransitiveCompletionWithSomeSupersMissingCompletes() throws IOException {
    compile(SimulatorTestSources.SUBCLASS);

    ruleInfo.addAvailableRule("//com/facebook/grandsuper:grandsuper");
    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/iface2:iface2");
    ruleInfo.addAvailableRule("//com/facebook/grandinterface:grandinterface");

    CompletedType result = completeSubclass(false);

    assertThat(result.getMissingDependencies(), Matchers.empty());
    assertEquals(CompletedTypeKind.COMPLETED_TYPE, result.kind);
  }

  @Test
  public void testTransitiveMemberClassCompletionWithSomeOuterSupersMissingCompletesPartially()
      throws IOException {
    compile(SimulatorTestSources.SUBCLASS);

    ruleInfo.addAvailableRule("//com/facebook/superclass:superclass");
    ruleInfo.addAvailableRule("//com/facebook/grandsuper:grandsuper");
    ruleInfo.addAvailableRule("//com/facebook/iface2:iface2");
    ruleInfo.addAvailableRule("//com/facebook/grandinterface:grandinterface");

    CompletedType result = completeSubclassMember(true);

    assertThat(
        result.getMissingDependencies(),
        Matchers.containsInAnyOrder("//com/facebook/iface1:iface1"));
    assertEquals(CompletedTypeKind.PARTIALLY_COMPLETED_TYPE, result.kind);
  }

  @Test
  public void testNonTransitiveMemberClassCompletionWithSomeOuterSupersMissingCompletes()
      throws IOException {
    compile(SimulatorTestSources.SUBCLASS);

    ruleInfo.addAvailableRule("//com/facebook/superclass:superclass");
    ruleInfo.addAvailableRule("//com/facebook/grandsuper:grandsuper");
    ruleInfo.addAvailableRule("//com/facebook/iface2:iface2");
    ruleInfo.addAvailableRule("//com/facebook/grandinterface:grandinterface");

    CompletedType result = completeSubclassMember(false);

    assertThat(result.getMissingDependencies(), Matchers.empty());
    assertEquals(CompletedTypeKind.COMPLETED_TYPE, result.kind);
  }

  @Test
  public void testTransitiveCompletionWithSomeSupersPresentButGrandSupersMissingCrashes()
      throws IOException {
    compile(SimulatorTestSources.SUBCLASS);

    ruleInfo.addAvailableRule("//com/facebook/superclass:superclass");
    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/iface2:iface2");

    CompletedType result = completeSubclass(true);
    assertThat(
        result.getMissingDependencies(),
        Matchers.containsInAnyOrder(
            "//com/facebook/grandsuper:grandsuper",
            "//com/facebook/grandinterface:grandinterface"));
    assertEquals(CompletedTypeKind.CRASH, result.kind);
  }

  @Test
  public void testNonTransitiveCompletionWithSomeSupersPresentButGrandSupersMissingCompletes()
      throws IOException {
    compile(SimulatorTestSources.SUBCLASS);

    ruleInfo.addAvailableRule("//com/facebook/superclass:superclass");
    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/iface2:iface2");

    CompletedType result = completeSubclass(false);
    assertThat(result.getMissingDependencies(), Matchers.empty());
    assertEquals(CompletedTypeKind.COMPLETED_TYPE, result.kind);
  }

  @Test
  public void
      testTransitiveMemberClassCompletionWithSomeOuterSupersPresentButGrandSupersMissingCrashes()
          throws IOException {
    compile(SimulatorTestSources.SUBCLASS);

    ruleInfo.addAvailableRule("//com/facebook/superclass:superclass");
    ruleInfo.addAvailableRule("//com/facebook/grandsuper:grandsuper");
    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/iface2:iface2");

    CompletedType result = completeSubclassMember(true);
    assertThat(
        result.getMissingDependencies(),
        Matchers.containsInAnyOrder("//com/facebook/grandinterface:grandinterface"));
    assertEquals(CompletedTypeKind.CRASH, result.kind);
  }

  @Test
  public void
      testNonTransitiveMemberClassCompletionWithSomeOuterSupersPresentButGrandSupersMissingCompletes()
          throws IOException {
    compile(SimulatorTestSources.SUBCLASS);

    ruleInfo.addAvailableRule("//com/facebook/superclass:superclass");
    ruleInfo.addAvailableRule("//com/facebook/grandsuper:grandsuper");
    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/iface2:iface2");

    CompletedType result = completeSubclassMember(false);
    assertThat(result.getMissingDependencies(), Matchers.empty());
    assertEquals(CompletedTypeKind.COMPLETED_TYPE, result.kind);
  }

  @Test
  public void testTransitiveCompletionOfMissingDepClassWithSomeSupersMissingReturnsErrorType()
      throws IOException {
    withClasspath(SimulatorTestSources.SUBCLASS);
    initCompiler();

    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/grandinterface:grandinterface");

    CompletedType result = completeSubclass(true);
    assertThat(
        result.getMissingDependencies(),
        Matchers.containsInAnyOrder(
            "//com/facebook/subclass:subclass",
            "//com/facebook/superclass:superclass",
            "//com/facebook/grandsuper:grandsuper",
            "//com/facebook/iface2:iface2"));
    assertEquals(CompletedTypeKind.ERROR_TYPE, result.kind);
  }

  @Test
  public void testNonTransitiveCompletionOfMissingDepClassWithSomeSupersMissingReturnsErrorType()
      throws IOException {
    withClasspath(SimulatorTestSources.SUBCLASS);
    initCompiler();

    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/grandinterface:grandinterface");

    CompletedType result = completeSubclass(false);
    assertThat(
        result.getMissingDependencies(),
        Matchers.containsInAnyOrder("//com/facebook/subclass:subclass"));
    assertEquals(CompletedTypeKind.ERROR_TYPE, result.kind);
  }

  @Test
  public void testTransitiveCompletionOfDepClassWithSomeSupersMissingCrashes() throws IOException {
    withClasspath(SimulatorTestSources.SUBCLASS);
    initCompiler();

    ruleInfo.addAvailableRule("//com/facebook/subclass:subclass");
    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/grandinterface:grandinterface");

    CompletedType result = completeSubclass(true);
    assertThat(
        result.getMissingDependencies(),
        Matchers.containsInAnyOrder(
            "//com/facebook/superclass:superclass",
            "//com/facebook/grandsuper:grandsuper",
            "//com/facebook/iface2:iface2"));
    assertEquals(CompletedTypeKind.CRASH, result.kind);
  }

  @Test
  public void testNonTransitiveCompletionOfDepClassWithSomeSupersMissingReturnsCompletedType()
      throws IOException {
    withClasspath(SimulatorTestSources.SUBCLASS);
    initCompiler();

    ruleInfo.addAvailableRule("//com/facebook/subclass:subclass");
    ruleInfo.addAvailableRule("//com/facebook/iface1:iface1");
    ruleInfo.addAvailableRule("//com/facebook/grandinterface:grandinterface");

    CompletedType result = completeSubclass(false);
    assertThat(result.getMissingDependencies(), Matchers.empty());
    assertEquals(CompletedTypeKind.COMPLETED_TYPE, result.kind);
  }

  private CompletedType completeSubclass(boolean transitive) {
    TypeElement subclass = elements.getTypeElement("com.facebook.subclass.Subclass");

    return completer.complete(subclass, transitive);
  }

  private CompletedType completeSubclassMember(boolean transitive) {
    TypeElement subclass = elements.getTypeElement("com.facebook.subclass.Subclass.SubclassMember");

    return completer.complete(subclass, transitive);
  }
}
