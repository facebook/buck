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

package com.facebook.buck.event.isolated;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.google.protobuf.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class DurationUtilsTest {

  @Test
  public void millisToDurationWhereNumberOfMillisRepresentSecond() {
    int seconds = 123;
    Duration duration = DurationUtils.millisToDuration(TimeUnit.SECONDS.toMillis(seconds));
    assertThat(duration.getSeconds(), equalTo((long) seconds));
    assertThat(duration.getNanos(), equalTo(0));
  }

  @Test
  public void millisToDurationWhereNumberOfMillisRepresentSecondWithRemainder() {
    int seconds = 123;
    int extraMillis = 789;
    int totalMillis = (int) (TimeUnit.SECONDS.toMillis(seconds) + extraMillis);

    Duration duration = DurationUtils.millisToDuration(totalMillis);
    assertThat(duration.getSeconds(), equalTo((long) seconds));
    assertThat(duration.getNanos(), equalTo((int) TimeUnit.MILLISECONDS.toNanos(extraMillis)));
  }
}
