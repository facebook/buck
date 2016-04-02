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

package com.facebook.buck.cxx;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

import java.util.regex.Pattern;

/**
 * Defines how to handle headers that get included during the build but aren't explicitly tracked
 * in any build files.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractHeaderVerification {

  public static final HeaderVerification NONE =
      HeaderVerification.builder().setMode(Mode.IGNORE).build();

  public abstract Mode getMode();

  /**
   * @return a list of regexes which match headers which should be exempt from verification.
   */
  protected abstract ImmutableList<Pattern> getWhitelist();

  /**
   * @return whether the given header has been whitelisted.
   */
  public boolean isWhitelisted(String header) {
    for (Pattern pattern : getWhitelist()) {
      if (pattern.matcher(header).matches()) {
        return true;
      }
    }
    return false;
  }

  public enum Mode {

    /**
     * Allow untracked headers.
     */
    IGNORE,

    /**
     * Warn on untracked headers.
     */
    WARN,

    /**
     * Error on untracked headers.
     */
    ERROR,

  }

}
