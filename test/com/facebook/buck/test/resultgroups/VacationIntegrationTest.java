/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.test.resultgroups;

import static com.facebook.buck.test.resultgroups.VacationFixture.CURRENCY_TEST;
import static com.facebook.buck.test.resultgroups.VacationFixture.HAVEMIND_TEST;
import static com.facebook.buck.test.resultgroups.VacationFixture.LOCATION_TEST;
import static com.facebook.buck.test.resultgroups.VacationFixture.MEDICINE_TEST;
import static com.facebook.buck.test.resultgroups.VacationFixture.PASSPORT_TEST;
import static com.facebook.buck.test.resultgroups.VacationFixture.READBOOK_TEST;
import static com.facebook.buck.test.resultgroups.VacationFixture.ResultType;
import static com.facebook.buck.test.resultgroups.VacationFixture.TestName;
import static com.facebook.buck.test.resultgroups.VacationFixture.VACATION_TEST;
import static com.facebook.buck.test.resultgroups.VacationFixture.WEEPHOTO_TEST;
import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.io.IOException;
import java.util.Map;

public class VacationIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Rule
  public VacationFixture vacationFixture = new VacationFixture(temporaryFolder);

  @Rule
  public RuleChain rule = RuleChain
      .outerRule(temporaryFolder)
      .around(vacationFixture);

  @Test
  public void seeMultipleFailuresWhenNotUsingDrop() throws IOException {
    vacationFixture.setTestToFail(WEEPHOTO_TEST);
    vacationFixture.setTestToFail(PASSPORT_TEST);
    vacationFixture.setTestToFail(VACATION_TEST);

    Map<TestName, ResultType> results = vacationFixture.runAllTests();

    assertResult(results, ResultType.FAIL,
        WEEPHOTO_TEST, PASSPORT_TEST, VACATION_TEST);
    assertResult(results, ResultType.PASS,
        LOCATION_TEST, READBOOK_TEST, HAVEMIND_TEST, MEDICINE_TEST, CURRENCY_TEST);
  }

  @Test
  public void isolatedLeafTestFailureIsIsolated() throws IOException {
    vacationFixture.setTestToFail(WEEPHOTO_TEST);

    Map<TestName, ResultType> results = vacationFixture.runAllTestsWithDrop();

    assertResult(results, ResultType.FAIL, WEEPHOTO_TEST);
    assertResult(results, ResultType.PASS,
        PASSPORT_TEST, VACATION_TEST, LOCATION_TEST, READBOOK_TEST,
        HAVEMIND_TEST, MEDICINE_TEST, CURRENCY_TEST);
  }

  @Test
  public void childFailureMeansParentFailuresAreDropsNotFails() throws IOException {
    vacationFixture.setTestToFail(WEEPHOTO_TEST);
    vacationFixture.setTestToFail(PASSPORT_TEST);
    vacationFixture.setTestToFail(VACATION_TEST);

    Map<TestName, ResultType> results = vacationFixture.runAllTestsWithDrop();

    assertResult(results, ResultType.FAIL, WEEPHOTO_TEST);
    assertResult(results, ResultType.DROP, PASSPORT_TEST, VACATION_TEST);
    assertResult(results, ResultType.PASS,
        LOCATION_TEST, READBOOK_TEST, HAVEMIND_TEST, MEDICINE_TEST, CURRENCY_TEST);
  }

  private void assertResult(
      Map<VacationFixture.TestName,VacationFixture.ResultType> results,
      ResultType expectedResultType,
      TestName... testNames) {
    for (TestName testName : testNames) {
      assertTrue(String.format("We don't have a test result for test '%s'!", testName),
          results.containsKey(testName));
      ResultType actualResultType = results.get(testName);
      assertThat(String.format("Test result for '%s'", testName),
          actualResultType, equalTo(expectedResultType));
    }
  }
}
