/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.cli.exception.handlers;

import com.facebook.buck.cli.exceptions.handlers.HumanReadableExceptionAugmentor;
import com.google.common.collect.ImmutableMap;
import java.util.regex.Pattern;
import org.junit.Assert;
import org.junit.Test;

public class HumanReadableExceptionAugmentorTest {

  @Test
  public void addsErrorMessageIfInvalidRegexProvided() {
    HumanReadableExceptionAugmentor augmentor =
        new HumanReadableExceptionAugmentor(
            ImmutableMap.of(
                Pattern.compile("Replace (.*) with something else"), "Should replace $1"));
    String error = augmentor.getAugmentedError("Replace foo bar baz with something else");
    Assert.assertEquals(
        "Replace foo bar baz with something else\nShould replace foo bar baz", error);
  }

  @Test
  public void returnsOriginalMessageIfNoConfigGiven() {
    Assert.assertEquals(
        "foo bar!",
        new HumanReadableExceptionAugmentor(ImmutableMap.of()).getAugmentedError("foo bar!"));
  }

  @Test
  public void printsErrorIfInvalidRegexProvided() {
    HumanReadableExceptionAugmentor augmentor =
        new HumanReadableExceptionAugmentor(
            ImmutableMap.of(
                Pattern.compile("Replace (.*) with something else"), "Should replace $9"));
    String error = augmentor.getAugmentedError("Replace foo bar baz with something else");
    Assert.assertEquals(
        "Replace foo bar baz with something else\nCould not replace text \"Replace foo bar baz with something else\" with regex \"Should replace $9\": No group 9",
        error);
  }
}
