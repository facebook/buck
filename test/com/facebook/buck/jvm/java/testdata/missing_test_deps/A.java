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

package com.facebook.buck.example;

public class A {
  public void testSomethingMagical() {
    // We use the junit 3 naming convention since we expect the test runner
    // to pick it up, but it allows the class to have no dependencies on
    // junit at all. I'd be amazed if this worked in practice, since we don't
    // extend TestCase, but *shrugs* we'd hope for an output of "0" run,
    // right?
  }
}
