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

package com.facebook.buck.util;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DiscardableTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testDiscard() {
    Discardable<String> discardable = new Discardable<>("hello");
    assertEquals("hello", discardable.get());

    discardable.discard();
    thrown.expect(NullPointerException.class);
    thrown.expectMessage("Discardabled accessed after discard().");
    discardable.get();
  }
}
