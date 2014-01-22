/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.javacc;

import org.junit.Test;
import java.io.StringReader;

// This class depends on javacc-generated source code.
// Please run "ant compile-tests" if you hit undefined symbols in IntelliJ.

public class JavaCCTest {

  @Test
  public void test1() throws ParseException {
    Simple1 parser1 = new Simple1(new StringReader("{}\n"));
    parser1.Input();
  }

  @Test(expected = ParseException.class)
  public void test2() throws ParseException {
      Simple1 parser2 = new Simple1(new StringReader("{{{\n"));
      parser2.Input();
  }

}
