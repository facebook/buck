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

package com.facebook.buck.testutil;

import java.util.Optional;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

public class OptionalMatchers {

  private OptionalMatchers() {}

  public static <T> TypeSafeDiagnosingMatcher<Optional<T>> empty() {
    return new TypeSafeDiagnosingMatcher<Optional<T>>() {

      @Override
      protected boolean matchesSafely(Optional<T> optional, Description description) {
        if (optional.isPresent()) {
          description.appendText("optional contents was ").appendValue(optional.get());
          return false;
        } else {
          return true;
        }
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("optional should be empty");
      }
    };
  }

  public static <T> TypeSafeDiagnosingMatcher<Optional<T>> present(Matcher<T> matcher) {
    return new TypeSafeDiagnosingMatcher<Optional<T>>() {

      @Override
      protected boolean matchesSafely(Optional<T> optional, Description description) {
        if (optional.isPresent()) {
          if (matcher.matches(optional.get())) {
            return true;
          } else {
            description.appendText("optional contents ");
            matcher.describeMismatch(optional.get(), description);
            return false;
          }

        } else {
          description.appendText("optional was empty");
          return false;
        }
      }

      @Override
      public void describeTo(Description description) {
        description.appendText("optional should contain ").appendDescriptionOf(matcher);
      }
    };
  }
}
