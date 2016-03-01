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

package com.facebook.buck.rage;

import static org.junit.Assert.assertThat;

import com.facebook.buck.util.CapturingPrintStream;
import com.google.common.base.Charsets;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

public class UserInputTest {
  @Test
  public void testAsk() throws Exception {
    String cannedAnswer = "answer";
    Fixture fixture = new Fixture(cannedAnswer);
    UserInput input = fixture.getUserInput();
    CapturingPrintStream outputStream = fixture.getOutputStream();

    String questionString = "question";
    String response = input.ask(questionString);
    assertThat(
        outputStream.getContentsAsString(Charsets.UTF_8),
        Matchers.containsString(questionString));
    assertThat(response, Matchers.equalTo(cannedAnswer));
  }

  @Test
  public void parseRange() {
    assertThat(
        UserInput.parseRange("0"),
        Matchers.contains(0)
    );

    assertThat(
        UserInput.parseRange("32"),
        Matchers.contains(32)
    );

    assertThat(
        UserInput.parseRange("2 3"),
        Matchers.contains(2, 3)
    );

    assertThat(
        UserInput.parseRange("2, 3"),
        Matchers.contains(2, 3)
    );

    assertThat(
        UserInput.parseRange("0-3"),
        Matchers.contains(0, 1, 2, 3)
    );
  }

  @Test
  public void parseRangeInteractive() throws Exception {
    Fixture fixture = new Fixture("1, 2-4, 9");
    assertThat(
        fixture.getUserInput().selectRange(
            "selectrangequery",
            ImmutableList.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"),
            Functions.<String>identity()),
        Matchers.contains("b", "c", "d", "e", "j"));

  }

  private static class Fixture {
    private CapturingPrintStream outputStream;
    private UserInput userInput;

    public Fixture(String cannedAnswer) throws Exception {
      outputStream = new CapturingPrintStream();
      InputStream inputStream = new ByteArrayInputStream((cannedAnswer + "\n").getBytes("UTF-8"));

      userInput = new UserInput(
          outputStream,
          new BufferedReader(new InputStreamReader(inputStream)));
    }

    public CapturingPrintStream getOutputStream() {
      return outputStream;
    }

    public UserInput getUserInput() {
      return userInput;
    }
  }
}
