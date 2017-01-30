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

package com.example;

import org.junit.Ignore;
import org.junit.Test;

/** This is an interface, not a class, so test won't be run. */
public interface InterfaceTest {
  @Test @Ignore
  default void testThatIsIgnored() {
    // pass
  }

  @Test
  default void testThatIsNotIgnored() {
    // would pass in a class that implemented this interface
  }
}
