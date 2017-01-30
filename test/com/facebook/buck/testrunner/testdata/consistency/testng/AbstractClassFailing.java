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

import org.testng.annotations.Test;

public abstract class AbstractClassFailing {

  public static class StaticInnerClassWithAFailingTest {
    @Test(enabled = false)
    public void testThatIsIgnored() {
      throw new AssertionError("should be ignored");
    }

    @Test
    public void testThatIsNotIgnored() {
      throw new AssertionError("should not be ignored");
    }
  }

  /** Can't be instantiated because constructor throws exception. */
  public static class StaticInnerClassThatFailsToInstantiate {
    public StaticInnerClassThatFailsToInstantiate() {
      throw new AssertionError("Class fails to instantiate");
    }
    @Test
    public void failsBecauseClassCannotBeInstantiated() {
      // empty
    }
  }

  /** Can't be instantiated because outer class is abstract. */
  public class NonStaticInnerClassThatCannotBeInstantiated {
    @Test
    public void failsBecauseClassCannotBeInstantiated() {
      // empty
    }
  }
}
