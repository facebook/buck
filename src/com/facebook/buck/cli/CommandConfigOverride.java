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

package com.facebook.buck.cli;

/**
 * Helper class for {@link com.facebook.buck.cli.AbstractCommand} which holds a buckconfig option or
 * a path to buckconfig file.
 */
abstract class CommandConfigOverride {
  private CommandConfigOverride() {}

  /** Buckconfig key and value. */
  public static class Config extends CommandConfigOverride {
    private final String key;
    private final String value;

    public Config(String key, String value) {
      this.key = key;
      this.value = value;
    }

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.config(key, value);
    }
  }

  /** Path to a file which buckconfig options. */
  public static class File extends CommandConfigOverride {
    private final String path;

    public File(String path) {
      this.path = path;
    }

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.file(path);
    }
  }

  /** Callback for {@link #match(Matcher)} operation. */
  public interface Matcher<R> {
    /** The variant is a pair of name and value. */
    R config(String key, String value);

    /** The variant is file path. */
    R file(String path);
  }

  /** Invoke different callback depend on this variant kind. */
  public abstract <R> R match(Matcher<R> matcher);
}
