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

package com.facebook.buck.core.rules.providers.lib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TestInfoTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private static final String TEST_NAME = "something_test";
  private static final String TEST_CASE_NAME = "foo.sh";
  private static final String TYPE = "json";

  @Test
  public void errorOnInvalidLabelsType() throws EvalException {
    Object labels = SkylarkList.createImmutable(ImmutableList.of("label1", "label2", 1));
    SkylarkList<String> contacts = SkylarkList.createImmutable(ImmutableList.of("foo@example.com"));
    thrown.expect(EvalException.class);
    // Broken cast, but this can apparently happen, so... verify :)
    TestInfo.instantiateFromSkylark(
        TEST_NAME,
        TEST_CASE_NAME,
        (SkylarkList<String>) labels,
        contacts,
        Runtime.NONE,
        false,
        TYPE,
        Location.BUILTIN);
  }

  @Test
  public void errorOnInvalidContactsType() throws EvalException {
    SkylarkList<String> labels = SkylarkList.createImmutable(ImmutableList.of("label1", "label2"));
    Object contacts = SkylarkList.createImmutable(ImmutableList.of("foo@example.com", 1));
    thrown.expect(EvalException.class);
    // Broken cast, but this can apparently happen, so... verify :)
    TestInfo.instantiateFromSkylark(
        TEST_NAME,
        TEST_CASE_NAME,
        labels,
        (SkylarkList<String>) contacts,
        Runtime.NONE,
        false,
        TYPE,
        Location.BUILTIN);
  }

  @Test
  public void errorOnInvalidTimeoutMsType() throws EvalException {
    SkylarkList<String> labels = SkylarkList.createImmutable(ImmutableList.of("label1", "label2"));
    SkylarkList<String> contacts = SkylarkList.createImmutable(ImmutableList.of("foo@example.com"));
    thrown.expect(EvalException.class);
    TestInfo.instantiateFromSkylark(
        TEST_NAME, TEST_CASE_NAME, labels, contacts, "not an int", false, TYPE, Location.BUILTIN);
  }

  @Test
  public void usesDefaultSkylarkValues() throws InterruptedException, EvalException {
    Object raw;
    try (Mutability mutability = Mutability.create("providertest")) {
      Environment env =
          Environment.builder(mutability)
              .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
              .setGlobals(
                  Environment.GlobalFrame.createForBuiltins(
                      ImmutableMap.of(TestInfo.PROVIDER.getName(), TestInfo.PROVIDER)))
              .build();

      raw =
          BuildFileAST.eval(
              env,
              String.format(
                  "TestInfo(test_name=\"%s\", test_case_name=\"%s\")", TEST_NAME, TEST_CASE_NAME));
    }
    assertTrue(raw instanceof TestInfo);
    TestInfo testInfo = (TestInfo) raw;

    assertEquals(ImmutableSet.of(), testInfo.labels());
    assertEquals(ImmutableSet.of(), testInfo.contacts());
    assertEquals(Runtime.NONE, testInfo.timeoutMs());
    assertEquals("custom", testInfo.type());
    assertFalse(testInfo.runTestsSeparately());
    assertEquals(TEST_NAME, testInfo.testName());
    assertEquals(TEST_CASE_NAME, testInfo.testCaseName());
  }

  @Test
  public void instantiatesFromSkylarkProperly() throws EvalException, InterruptedException {
    Object raw;
    try (Mutability mutability = Mutability.create("providertest")) {
      Environment env =
          Environment.builder(mutability)
              .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
              .setGlobals(
                  Environment.GlobalFrame.createForBuiltins(
                      ImmutableMap.of(
                          TestInfo.PROVIDER.getName(),
                          TestInfo.PROVIDER,
                          "None",
                          Runtime.NONE,
                          "True",
                          true)))
              .build();

      String buildFile =
          String.format(
              "TestInfo("
                  + "\ntest_name=\"%s\","
                  + "\ntest_case_name=\"%s\","
                  + "\nlabels = [\"label1\", \"label2\", \"label1\"],"
                  + "\ncontacts = [\"foo@example.com\", \"bar@example.com\", \"foo@example.com\"],"
                  + "\nrun_tests_separately=True,"
                  + "\ntimeout_ms=5,"
                  + "\ntype=\"%s\""
                  + "\n)",
              TEST_NAME, TEST_CASE_NAME, TYPE);

      raw = BuildFileAST.eval(env, buildFile);
    }
    assertTrue(raw instanceof TestInfo);
    TestInfo testInfo = (TestInfo) raw;

    assertEquals(ImmutableSet.of("label1", "label2"), testInfo.labels());
    assertEquals(ImmutableSet.of("foo@example.com", "bar@example.com"), testInfo.contacts());
    assertEquals(TEST_NAME, testInfo.testName());
    assertEquals(TEST_CASE_NAME, testInfo.testCaseName());
    assertEquals(Optional.of(5L), testInfo.typedTimeoutMs());
    assertTrue(testInfo.runTestsSeparately());
    assertEquals(TYPE, testInfo.type());
  }

  @Test
  public void coercesTimeout() throws InterruptedException, EvalException {
    Object raw1;
    Object raw2;
    try (Mutability mutability = Mutability.create("providertest")) {
      Environment env =
          Environment.builder(mutability)
              .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
              .setGlobals(
                  Environment.GlobalFrame.createForBuiltins(
                      ImmutableMap.of(TestInfo.PROVIDER.getName(), TestInfo.PROVIDER)))
              .build();

      raw1 =
          BuildFileAST.eval(
              env,
              String.format(
                  "TestInfo("
                      + "\ntest_name=\"%s\","
                      + "\ntest_case_name=\"%s\","
                      + "\ntimeout_ms=TestInfo(test_name=\"%s\", test_case_name=\"%s\").timeout_ms"
                      + "\n)",
                  TEST_NAME, TEST_CASE_NAME, TEST_NAME, TEST_CASE_NAME));
      raw2 =
          BuildFileAST.eval(
              env,
              String.format(
                  "TestInfo("
                      + "\ntest_name=\"%s\","
                      + "\ntest_case_name=\"%s\","
                      + "\ntimeout_ms=TestInfo(test_name=\"%s\", test_case_name=\"%s\", timeout_ms=5).timeout_ms"
                      + "\n)",
                  TEST_NAME, TEST_CASE_NAME, TEST_NAME, TEST_CASE_NAME));
    }
    assertThat(raw1, Matchers.instanceOf(TestInfo.class));
    TestInfo val1 = (TestInfo) raw1;
    assertEquals(Runtime.NONE, val1.timeoutMs());
    assertEquals(Optional.empty(), val1.typedTimeoutMs());

    assertThat(raw2, Matchers.instanceOf(TestInfo.class));
    TestInfo val2 = (TestInfo) raw2;
    assertEquals(5, val2.timeoutMs());
    assertEquals(Optional.of(5L), val2.typedTimeoutMs());
  }
}
