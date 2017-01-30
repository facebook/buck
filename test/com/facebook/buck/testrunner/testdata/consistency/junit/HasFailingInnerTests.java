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

/** Although this class doesn't have any tests, inner classes do. */
public class HasFailingInnerTests {

  public static abstract class AbstractInner {
    @Test @Ignore
    public void testThatIsIgnored() {
      throw new AssertionError("should be ignored");
    }

    @Test
    public void testThatIsNotIgnored() {
      throw new AssertionError("should fail");
    }
  }

  public static class ConcreteInner extends AbstractInner {
  }

  // Should force creation of an anonymous inner class
  private static AbstractInner forceAnonymousClass = new AbstractInner() {
    @Test
    public void anotherTestThatIsNotIgnored() {
      throw new AssertionError("should fail");
    }
  };
}
