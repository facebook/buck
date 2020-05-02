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

package com.facebook.buck.core.rules.configsetting;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuckConfigKeyTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void parse() {
    assertEquals(BuckConfigKey.of("ab", "cd"), BuckConfigKey.parse("ab.cd"));
  }

  @Test
  public void parseException() {
    thrown.expect(IllegalArgumentException.class);

    BuckConfigKey.parse("ab");
  }
}
