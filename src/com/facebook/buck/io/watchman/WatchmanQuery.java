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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/** Enumerate all watchman queries. */
public abstract class WatchmanQuery {

  private WatchmanQuery() {}

  public abstract ImmutableList<Object> toProtocolArgs();

  /** {@code query} query. */
  @BuckStyleValue
  public abstract static class Query extends WatchmanQuery {
    public abstract String getPath();

    public abstract ImmutableMap<String, Object> getArgs();

    @Override
    public ImmutableList<Object> toProtocolArgs() {
      return ImmutableList.of("query", getPath(), getArgs());
    }
  }

  /** {@code version} query. */
  @BuckStyleValue
  public abstract static class Version extends WatchmanQuery {
    public abstract ImmutableMap<String, Object> getArgs();

    @Override
    public ImmutableList<Object> toProtocolArgs() {
      return ImmutableList.of("version", getArgs());
    }
  }

  /** {@code watch-project} query. */
  @BuckStyleValue
  public abstract static class WatchProject extends WatchmanQuery {
    public abstract String getPath();

    @Override
    public ImmutableList<Object> toProtocolArgs() {
      return ImmutableList.of("watch-project", getPath());
    }
  }

  /** {@code watch} query. */
  @BuckStyleValue
  public abstract static class Watch extends WatchmanQuery {
    public abstract String getPath();

    @Override
    public ImmutableList<Object> toProtocolArgs() {
      return ImmutableList.of("watch", getPath());
    }
  }

  /** {@code clock} query. */
  @BuckStyleValue
  public abstract static class Clock extends WatchmanQuery {
    public abstract String getPath();

    public abstract ImmutableMap<String, Object> getArgs();

    @Override
    public ImmutableList<Object> toProtocolArgs() {
      return ImmutableList.of("clock", getPath(), getArgs());
    }
  }

  /** {@code get-pid} query. */
  @BuckStyleValue
  public abstract static class GetPid extends WatchmanQuery {
    @Override
    public ImmutableList<Object> toProtocolArgs() {
      return ImmutableList.of("get-pid");
    }
  }

  /** {@code query} query. */
  public static Query query(String path, ImmutableMap<String, Object> params) {
    return ImmutableQuery.ofImpl(path, params);
  }

  /** {@code clock} query. */
  public static Clock clock(String path, ImmutableMap<String, Object> args) {
    return ImmutableClock.ofImpl(path, args);
  }

  /** {@code watch-project} query. */
  public static WatchProject watchProject(String path) {
    return ImmutableWatchProject.ofImpl(path);
  }

  /** {@code watch} query. */
  public static Watch watch(String path) {
    return ImmutableWatch.ofImpl(path);
  }

  /** {@code version} query. */
  public static Version version(ImmutableMap<String, Object> params) {
    return ImmutableVersion.ofImpl(params);
  }

  /** {@code get-pid} query. */
  public static GetPid getPid() {
    return ImmutableGetPid.of();
  }
}
