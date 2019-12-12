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

package com.facebook.buck.core.exceptions;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.Test;

public class HumanReadableExceptionsTest {

  @Test
  public void rethrowIfHumanReadableUnchecked_HumanReadableException() {
    Throwable e = new HumanReadableException("oh my");
    try {
      HumanReadableExceptions.throwIfHumanReadableUnchecked(e);
      fail("expecting to be rethrown");
    } catch (HumanReadableException x) {
      assertSame(e, x);
    }
  }

  @Test
  public void rethrowIfHumanReadableUnchecked_HumanReadableException_subclass() {
    class SubclassException extends HumanReadableException {

      public SubclassException() {
        super("test test");
      }
    }

    Throwable e = new SubclassException();
    try {
      HumanReadableExceptions.throwIfHumanReadableUnchecked(e);
      fail("expecting to be rethrown");
    } catch (SubclassException x) {
      assertSame(e, x);
    }
  }

  @Test
  public void rethrowIfHumanReadableUnchecked_ExceptionWithHumanReadableMessage_unchecked() {
    class UncheckedException extends RuntimeException implements ExceptionWithHumanReadableMessage {

      @Override
      public String getHumanReadableErrorMessage() {
        return "message to human";
      }
    }

    Throwable e = new UncheckedException();
    try {
      HumanReadableExceptions.throwIfHumanReadableUnchecked(e);
      fail("expecting to be rethrown");
    } catch (UncheckedException x) {
      assertSame(e, x);
    }
  }

  @Test
  public void rethrowIfHumanReadableUnchecked_othersAreNotRethrown() {
    HumanReadableExceptions.throwIfHumanReadableUnchecked(new RuntimeException());
    HumanReadableExceptions.throwIfHumanReadableUnchecked(new ClassCastException());

    class CheckedException extends Exception implements ExceptionWithHumanReadableMessage {
      @Override
      public String getHumanReadableErrorMessage() {
        return "another message";
      }
    }

    HumanReadableExceptions.throwIfHumanReadableUnchecked(new CheckedException());
  }
}
