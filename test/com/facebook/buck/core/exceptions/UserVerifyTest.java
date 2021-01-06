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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class UserVerifyTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void verifyNotNullThrowsOnNull() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Your message here");
    UserVerify.verifyNotNull(null, "Your message here");
  }

  @Test
  public void verifyNotNullThrowsOnNullWithFormatString() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Message with args: 1 foo bar");
    UserVerify.verifyNotNull(null, "Message with args: %s %s", 1, "foo bar");
  }

  @Test
  public void verifyNotNullReturnsObjectOnNotNull() {
    String someString = "blarg";
    assertSame(someString, UserVerify.verifyNotNull(someString, "Your message here"));
  }

  @Test
  public void verifyNotNullReturnsObjectOnNotNullWithFormatString() {
    String someString = "blarg";
    assertSame(
        someString, UserVerify.verifyNotNull(someString, "Message with args: %s %s", 1, "foo bar"));
  }

  @Test
  public void verifyThrowsOnFalse() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Your message here");
    UserVerify.verify(false, "Your message here");
  }

  @Test
  public void verifyThrowsOnFalseWithFormatString() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Message with args: 1 foo bar");
    UserVerify.verify(false, "Message with args: %s %s", 1, "foo bar");
  }

  @Test
  public void verifyReturnsOnTrue() {
    UserVerify.verify(true, "Your message here");
  }

  @Test
  public void verifyReturnsOnTrueWithFormatString() {
    UserVerify.verify(true, "Message with args: %s %s", 1, "foo bar");
  }

  @Test
  public void checkArgumentThrowsOnFalse() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Your message here");
    UserVerify.checkArgument(false, "Your message here");
  }

  @Test
  public void checkArgumentThrowsOnFalseWithFormatString() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Message with args: 1 foo bar");
    UserVerify.checkArgument(false, "Message with args: %s %s", 1, "foo bar");
  }

  @Test
  public void checkArgumentReturnsOnTrue() {
    UserVerify.checkArgument(true, "Your message here");
  }

  @Test
  public void checkArgumentReturnsOnTrueWithFormatString() {
    UserVerify.checkArgument(true, "Message with args: %s %s", 1, "foo bar");
  }
}
