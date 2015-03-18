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

package com.facebook.buck.util;

import static org.hamcrest.Matchers.is;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;

public class MoreThrowablesTest {

  @Rule
  public ExpectedException expected = ExpectedException.none();

  @Test
  public void closedByInterruptException() throws InterruptedException {
    ClosedByInterruptException e = new ClosedByInterruptException();
    expected.expect(InterruptedException.class);
    expected.expect(CausedBy.causedBy(e));
    MoreThrowables.propagateIfInterrupt(e);
  }

  @Test
  public void interruptedIOException() throws InterruptedException {
    InterruptedIOException e = new InterruptedIOException();
    expected.expect(InterruptedException.class);
    expected.expect(CausedBy.causedBy(e));
    MoreThrowables.propagateIfInterrupt(e);
  }

  @Test
  public void interruptedException() throws InterruptedException {
    InterruptedException e = new InterruptedException();
    expected.expect(InterruptedException.class);
    expected.expect(is(e));
    MoreThrowables.propagateIfInterrupt(e);
  }

  @Test
  public void socketTimeoutException() throws InterruptedException {
    MoreThrowables.propagateIfInterrupt(new SocketTimeoutException());
  }

  @Test
  public void otherException() throws InterruptedException {
    MoreThrowables.propagateIfInterrupt(new IOException());
  }

  public static class CausedBy extends TypeSafeMatcher<Throwable> {

    private Throwable cause;

    public CausedBy(Throwable cause) {
      this.cause = cause;
    }

    @Override
    public boolean matchesSafely(Throwable throwable) {
      return throwable.getCause() == cause;
    }

    @Override
    public void describeTo(Description description) {
      description.appendText(String.format("caused by %s", cause.toString()));
    }

    @Factory
    public static <T> Matcher<Throwable> causedBy(Throwable throwable) {
      return new CausedBy(throwable);
    }

  }

}
