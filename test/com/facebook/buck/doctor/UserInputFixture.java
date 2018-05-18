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

package com.facebook.buck.doctor;

import com.facebook.buck.util.CapturingPrintStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class UserInputFixture {
  private CapturingPrintStream outputStream;
  private UserInput userInput;

  public UserInputFixture(String cannedAnswer) {
    outputStream = new CapturingPrintStream();
    InputStream inputStream =
        new ByteArrayInputStream((cannedAnswer + "\n").getBytes(StandardCharsets.UTF_8));

    userInput = new UserInput(outputStream, new BufferedReader(new InputStreamReader(inputStream)));
  }

  public CapturingPrintStream getOutputStream() {
    return outputStream;
  }

  public UserInput getUserInput() {
    return userInput;
  }
}
