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

package com.facebook.buck.artifact_cache;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.core.rulekey.RuleKey;
import org.hamcrest.Matchers;
import org.junit.Test;

public class TopLevelArtifactFetchEventTest {

  @Test
  public void testEquals() {
    TopLevelArtifactFetchEvent.Started started =
        TopLevelArtifactFetchEvent.newFetchStartedEvent(null, new RuleKey("aaaa"));
    TopLevelArtifactFetchEvent.Finished finished =
        TopLevelArtifactFetchEvent.newFetchSuccessEvent(
            started, CacheResult.of(CacheResultType.HIT, "random source"));
    assertThat(started.isRelatedTo(finished), Matchers.is(true));
    assertEquals(started.getEventKey(), finished.getEventKey());
    assertEquals(started.getRuleKey(), finished.getRuleKey());
    assertEquals(started.getTarget(), finished.getTarget());
    assertNotEquals(started, finished);
    assertNotEquals(started.getEventName(), finished.getEventName());
  }
}
