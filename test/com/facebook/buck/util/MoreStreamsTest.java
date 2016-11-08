/*
 * Copyright 2016-present Facebook, Inc.
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

import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MoreStreamsTest {
  @Test
  public void filterCastFiltersObjects() {
    // generate a stream of alternating strings and objects.
    Stream<Object> input = Stream.<Object>iterate("", previous -> {
      if (previous instanceof String) {
        return new Object();
      } else {
        return "";
      }
    }).limit(1000);

    List<String> collectedResult = input
        .flatMap(MoreStreams.filterCast(String.class))
        .collect(Collectors.toList());

    Assert.assertEquals(
        collectedResult,
        Stream.generate(() -> "").limit(500).collect(Collectors.toList()));
  }
}
