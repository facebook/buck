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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;

import org.junit.Test;

public class JsonMatcherTest {

  @Test
  public void testMatch() {
    String json1 = "{\"a\":{\"b\":1}, \"c\":\"d\", \"e\":[1, 2], \"f\": null}";
    String json2 = json1;
    assertThat(json1, new JsonMatcher(json2));

    json2 = "{\"f\": null, \"c\":\"d\", \"a\":{\"b\":1}, \"e\":[1, 2]}";
    assertThat(json1, new JsonMatcher(json2));
  }

  @Test
  public void testMismatch() {
    String json1 = "{\"a\":{\"b\":1}, \"c\":\"d\", \"e\":[1, 2]}";
    String json2 = "{\"a\":{\"b\":1}, \"c\":\"d\", \"e\":[1, 3]}";
    assertThat(json1, not(new JsonMatcher(json2)));

    json2 = "{\"a\":{\"b\":1}, \"e\":[1, 2]}";
    assertThat(json1, not(new JsonMatcher(json2)));

    json2 = "{\"a\":{\"b\":1}, \"c\":null, \"e\":[1, 2]}";
    assertThat(json1, not(new JsonMatcher(json2)));

    json2 = "{\"a\":{\"b\":1}, \"c\":\"f\", \"e\":[1, 2]}";
    assertThat(json1, not(new JsonMatcher(json2)));

    json2 = "{\"a\":{\"b\":0}, \"c\":\"d\", \"e\":[1, 2]}";
    assertThat(json1, not(new JsonMatcher(json2)));

    json2 = "{\"a\":{\"b\":1}, \"c\":\"d\", \"e\":[1, 2], \"f\": null}";
    assertThat(json1, not(new JsonMatcher(json2)));
  }
}
