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

package com.facebook.buck.doctor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.util.CapturingPrintStream;
import com.google.common.base.Charsets;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import org.hamcrest.Matchers;
import org.hamcrest.junit.ExpectedException;
import org.junit.Rule;
import org.junit.Test;

public class UserInputTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void testAsk() throws Exception {
    String cannedAnswer = "answer";
    UserInputFixture fixture = new UserInputFixture(cannedAnswer);
    UserInput input = fixture.getUserInput();
    CapturingPrintStream outputStream = fixture.getOutputStream();

    String questionString = "question";
    String response = input.ask(questionString);
    assertThat(
        outputStream.getContentsAsString(Charsets.UTF_8), Matchers.containsString(questionString));
    assertThat(response, Matchers.equalTo(cannedAnswer));
  }

  @Test
  public void parseOne() {
    assertEquals(UserInput.parseOne("3").intValue(), 3);
    assertEquals(UserInput.parseOne("0").intValue(), 0);

    expectedException.expect(IllegalArgumentException.class);
    UserInput.parseOne("");
  }

  @Test
  public void parseRange() {
    assertThat(UserInput.parseRange("0"), Matchers.contains(0));

    assertThat(UserInput.parseRange("32"), Matchers.contains(32));

    assertThat(UserInput.parseRange("2 3"), Matchers.contains(2, 3));

    assertThat(UserInput.parseRange("2, 3"), Matchers.contains(2, 3));

    assertThat(UserInput.parseRange("0-3"), Matchers.contains(0, 1, 2, 3));
  }

  @Test
  public void parseOneInteractive() throws Exception {
    UserInputFixture fixture = new UserInputFixture("1");
    assertThat(
        fixture
            .getUserInput()
            .selectRange("selectrangequery", ImmutableList.of("a", "b", "c"), Functions.identity()),
        Matchers.contains("b"));
  }

  @Test
  public void parseRangeInteractive() throws Exception {
    UserInputFixture fixture = new UserInputFixture("1, 2-4, 9");
    assertThat(
        fixture
            .getUserInput()
            .selectRange(
                "selectrangequery",
                ImmutableList.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"),
                Functions.identity()),
        Matchers.contains("b", "c", "d", "e", "j"));
  }
}
