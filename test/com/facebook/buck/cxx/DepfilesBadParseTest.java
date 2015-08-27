/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.util.HumanReadableException;

import org.junit.rules.ExpectedException;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;

public class DepfilesBadParseTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void parseDepfileWithMultipleRulesThrows() throws IOException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Depfile parser cannot handle .d file with multiple targets");
    Depfiles.parseDepfile(new StringReader("output: input\noutput2:input2\n"));
  }
}
