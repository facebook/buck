/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.parser.AbstractParserConfig.ApplyDefaultFlavorsMode;
import com.google.common.util.concurrent.ListeningExecutorService;
import org.immutables.builder.Builder;
import org.immutables.value.Value;

/**
 * Contains objects and information that may be used during processing a parsing request.
 *
 * <p>Note that some of the objects in this context may not be used during all of the requests. For
 * this reason this context is created using a builder pattern where only necessary parameters are
 * passed. Some of the parameters are mandatory and they must be passed to the {@code builder()}
 * method.
 */
@Value.Immutable(copy = true)
@BuckStyleImmutable
public abstract class AbstractParsingContext {

  /** Cell for which the parsing request is performed */
  @Builder.Parameter
  public abstract Cell getCell();

  /** An executor used during parsing request to perform async computations */
  @Builder.Parameter
  public abstract ListeningExecutorService getExecutor();

  /** Whether to enable profiling during parsing request. */
  @Value.Default
  public boolean isProfilingEnabled() {
    return false;
  }

  /**
   * Whether speculative parsing is enabled.
   *
   * <p>Speculative parsing a special mode of parsing when dependencies of a target are scheduled
   * for parsing ahead of the actual requests for parsing of those targets. This may lead to
   * over-parsing is some case and thus needs to be used in situations when all of the dependencies
   * of requested targets are used later.
   */
  @Value.Default
  public SpeculativeParsing getSpeculativeParsing() {
    return SpeculativeParsing.DISABLED;
  }

  /**
   * Whether targets with constraints that are not compatible with the target platform should be
   * excluded.
   */
  @Value.Default
  public boolean excludeUnsupportedTargets() {
    return false;
  }

  /**
   * Controls how flavors are appended to the build targets.
   *
   * @see ApplyDefaultFlavorsMode
   */
  @Value.Default
  public ApplyDefaultFlavorsMode getApplyDefaultFlavorsMode() {
    return ApplyDefaultFlavorsMode.DISABLED;
  }

  @Value.Default
  public boolean enableTargetCompatibilityChecks() {
    return true;
  }
}
