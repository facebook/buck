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

package com.facebook.buck.util.config;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.filesystems.ForwardRelPath;
import java.util.Optional;
import org.junit.Test;

public class RecursivePathSettingTest {

  @Test
  public void test() {
    RecursivePathSetting<Boolean> setting =
        RecursivePathSetting.<Boolean>builder()
            .set(ForwardRelPath.of("foo"), false)
            .set(ForwardRelPath.of("foo/bar"), true)
            .build();
    assertEquals(setting.get(ForwardRelPath.of("")), Optional.empty());
    assertEquals(setting.get(ForwardRelPath.of("bar")), Optional.empty());
    assertEquals(setting.get(ForwardRelPath.of("foo")), Optional.of(false));
    assertEquals(setting.get(ForwardRelPath.of("foo/baz")), Optional.of(false));
    assertEquals(setting.get(ForwardRelPath.of("foo/bar")), Optional.of(true));
    assertEquals(setting.get(ForwardRelPath.of("foo/bar/baz")), Optional.of(true));
  }
}
