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

import com.facebook.buck.core.starlark.testutil.TestStarlarkParser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkList;
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
    Object labels = StarlarkList.immutableCopyOf(ImmutableList.of("label1", "label2", 1));
    StarlarkList<String> contacts =
        StarlarkList.immutableCopyOf(ImmutableList.of("foo@example.com"));
    thrown.expect(EvalException.class);
    // Broken cast, but this can apparently happen, so... verify :)
    TestInfo.instantiateFromSkylark(
        TEST_NAME,
        TEST_CASE_NAME,
        (StarlarkList<String>) labels,
        contacts,
        Starlark.NONE,
        false,
        TYPE);
  }

  @Test
  public void errorOnInvalidContactsType() throws EvalException {
    StarlarkList<String> labels =
        StarlarkList.immutableCopyOf(ImmutableList.of("label1", "label2"));
    Object contacts = StarlarkList.immutableCopyOf(ImmutableList.of("foo@example.com", 1));
    thrown.expect(EvalException.class);
    // Broken cast, but this can apparently happen, so... verify :)
    TestInfo.instantiateFromSkylark(
        TEST_NAME,
        TEST_CASE_NAME,
        labels,
        (StarlarkList<String>) contacts,
        Starlark.NONE,
        false,
        TYPE);
  }

  @Test
  public void errorOnInvalidTimeoutMsType() throws EvalException {
    StarlarkList<String> labels =
        StarlarkList.immutableCopyOf(ImmutableList.of("label1", "label2"));
    StarlarkList<String> contacts =
        StarlarkList.immutableCopyOf(ImmutableList.of("foo@example.com"));
    thrown.expect(EvalException.class);
    TestInfo.instantiateFromSkylark(
        TEST_NAME, TEST_CASE_NAME, labels, contacts, "not an int", false, TYPE);
  }

  @Test
  public void usesDefaultSkylarkValues() throws Exception {
    Object raw =
        TestStarlarkParser.eval(
            String.format(
                "TestInfo(test_name=\"%s\", test_case_name=\"%s\")", TEST_NAME, TEST_CASE_NAME),
            ImmutableMap.of(TestInfo.PROVIDER.getName(), TestInfo.PROVIDER));
    assertTrue(raw instanceof TestInfo);
    TestInfo testInfo = (TestInfo) raw;

    assertEquals(ImmutableSet.of(), testInfo.labels());
    assertEquals(ImmutableSet.of(), testInfo.contacts());
    assertEquals(Starlark.NONE, testInfo.timeoutMs());
    assertEquals("custom", testInfo.type());
    assertFalse(testInfo.runTestsSeparately());
    assertEquals(TEST_NAME, testInfo.testName());
    assertEquals(TEST_CASE_NAME, testInfo.testCaseName());
  }

  @Test
  public void instantiatesFromSkylarkProperly() throws Exception {
    ImmutableMap<String, Object> map =
        ImmutableMap.of(TestInfo.PROVIDER.getName(), TestInfo.PROVIDER);

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

    Object raw = TestStarlarkParser.eval(buildFile, map);
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
  public void coercesTimeout() throws Exception {
    Object raw1;
    Object raw2;
    ImmutableMap<String, Object> map =
        ImmutableMap.of(TestInfo.PROVIDER.getName(), TestInfo.PROVIDER);

    raw1 =
        TestStarlarkParser.eval(
            String.format(
                "TestInfo("
                    + "\ntest_name=\"%s\","
                    + "\ntest_case_name=\"%s\","
                    + "\ntimeout_ms=TestInfo(test_name=\"%s\", test_case_name=\"%s\").timeout_ms"
                    + "\n)",
                TEST_NAME, TEST_CASE_NAME, TEST_NAME, TEST_CASE_NAME),
            map);
    raw2 =
        TestStarlarkParser.eval(
            String.format(
                "TestInfo("
                    + "\ntest_name=\"%s\","
                    + "\ntest_case_name=\"%s\","
                    + "\ntimeout_ms=TestInfo(test_name=\"%s\", test_case_name=\"%s\", timeout_ms=5).timeout_ms"
                    + "\n)",
                TEST_NAME, TEST_CASE_NAME, TEST_NAME, TEST_CASE_NAME),
            map);
    assertThat(raw1, Matchers.instanceOf(TestInfo.class));
    TestInfo val1 = (TestInfo) raw1;
    assertEquals(Starlark.NONE, val1.timeoutMs());
    assertEquals(Optional.empty(), val1.typedTimeoutMs());

    assertThat(raw2, Matchers.instanceOf(TestInfo.class));
    TestInfo val2 = (TestInfo) raw2;
    assertEquals(5, val2.timeoutMs());
    assertEquals(Optional.of(5L), val2.typedTimeoutMs());
  }
}
