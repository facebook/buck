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

package com.facebook.buck.io.watchman;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;

/** Responses for {@link WatchmanQuery}. */
public abstract class WatchmanQueryResp {

  /** Generic response. */
  // TODO: replace with specific types
  @BuckStyleValue
  public abstract static class Generic extends WatchmanQueryResp {
    public abstract ImmutableMap<String, Object> getResp();
  }

  /** Response to {@code watch-project} command. */
  @BuckStyleValue
  public abstract static class WatchProjectResp extends WatchmanQueryResp {
    public abstract String getWatch();

    public abstract String getRelativePath();
  }

  public static Generic generic(ImmutableMap<String, Object> resp) {
    return ImmutableGeneric.ofImpl(resp);
  }

  public static WatchProjectResp watchProject(String watch, String relativePath) {
    return ImmutableWatchProjectResp.ofImpl(watch, relativePath);
  }
}
