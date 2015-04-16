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

package com.facebook.buck.testutil;

import static org.hamcrest.core.IsEqual.equalTo;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Hamcrest matcher which checks if a collection contains another collection's items in order
 * inside.
 *
 * Unlike isIn(), contains(), and containsInAnyOrder(), this matcher matches if the
 * items to match are found in order with no gaps anywhere inside the collection.
 */
public class HasConsecutiveItemsMatcher<T>
    extends TypeSafeDiagnosingMatcher<Collection<? extends T>> {
  private final List<Matcher<? super T>> matchers;

  public HasConsecutiveItemsMatcher(List<Matcher<? super T>> matchers) {
    this.matchers = matchers;
  }

  @Override
  public boolean matchesSafely(
      Collection<? extends T> itemsToMatch,
      Description mismatchDescription) {
    List<? extends T> itemsToMatchList = new ArrayList<>(itemsToMatch);

    for (int i = 0; i <= itemsToMatchList.size() - matchers.size(); i++) {
      boolean allMatchersMatched = true;
      for (int j = 0; j < matchers.size(); j++) {
        Matcher<? super T> matcher = matchers.get(j);
        if (!matcher.matches(itemsToMatchList.get(i + j))) {
          allMatchersMatched = false;
          break;
        }
      }
      if (allMatchersMatched) {
        return true;
      }
    }

    mismatchDescription
        .appendText("could not find items inside collection ")
        .appendValueList("[", ", ", "]", itemsToMatch);
    return false;
  }

  @Override
  public void describeTo(Description description) {
    description
        .appendText("collection contains consecutive items matching ")
        .appendList("[", ", ", "]", matchers);
  }

  public static <T> Matcher<Collection<? extends T>> hasConsecutiveItems(
      List<Matcher<? super T>> matchers) {
    return new HasConsecutiveItemsMatcher<T>(matchers);
  }

  @SafeVarargs
  public static <T> Matcher<Collection<? extends T>> hasConsecutiveItems(
      Matcher<? super T>... matchers) {
    return hasConsecutiveItems(Arrays.asList(matchers));
  }

  public static <T> Matcher<Collection<? extends T>> hasConsecutiveItems(
      Iterable<? extends T> items) {
    List<Matcher<? super T>> matchers = new ArrayList<>();
    for (Object item : items) {
      matchers.add(equalTo(item));
    }
    return new HasConsecutiveItemsMatcher<T>(matchers);
  }

  @SafeVarargs
  public static <T> Matcher<Collection<? extends T>> hasConsecutiveItems(T... elements) {
    return hasConsecutiveItems(Arrays.asList(elements));
  }
}
