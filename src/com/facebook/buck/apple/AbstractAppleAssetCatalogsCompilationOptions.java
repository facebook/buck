/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

/** Argument type for asset_catalogs_compilation_options used in apple_bundle */
@BuckStyleImmutable
@Value.Immutable
abstract class AbstractAppleAssetCatalogsCompilationOptions implements AddsToRuleKey {

  /** Argument type for AppleAssetCatalogsCompilationOptions used in Actool */
  public enum Optimization {
    SPACE("space"),
    TIME("time"),
    ;

    private final String argument;

    Optimization(String argument) {
      this.argument = argument;
    }

    public String toArgument() {
      return argument;
    }
  }

  /** Argument type for AppleAssetCatalogsCompilationOptions used in Actool */
  public enum OutputFormat {
    BINARY1("binary1"),
    XML1("xml1"),
    HUMAN_READABLE_TEXT("human-readable-text"),
    ;

    private final String argument;

    OutputFormat(String argument) {
      this.argument = argument;
    }

    public String toArgument() {
      return argument;
    }
  }

  @Value.Default
  @AddToRuleKey
  boolean getNotices() {
    return true;
  }

  @Value.Default
  @AddToRuleKey
  boolean getWarnings() {
    return true;
  }

  @Value.Default
  @AddToRuleKey
  boolean getErrors() {
    return true;
  }

  @Value.Default
  @AddToRuleKey
  boolean getCompressPngs() {
    return true;
  }

  @Value.Default
  @AddToRuleKey
  Optimization getOptimization() {
    return Optimization.SPACE;
  }

  @Value.Default
  @AddToRuleKey
  OutputFormat getOutputFormat() {
    return OutputFormat.HUMAN_READABLE_TEXT;
  }

  @Value.Default
  @AddToRuleKey
  ImmutableList<String> getExtraFlags() {
    return ImmutableList.of();
  }
}
