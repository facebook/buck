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

package com.facebook.buck.parser.implicit;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Implicit include path for parser (Python DSL or Starlark). */
@BuckStyleValue
public abstract class ImplicitIncludePath {

  public abstract String getCellName();

  public abstract ForwardRelPath getCellRelPath();

  // Parsing is messy: sometimes cell name starts with `@` and sometimes it doesn't.
  // Sometimes last component of path is separated with `:` and sometimes is is not.
  private static final Pattern INCLUDE_PATH_PATTERN =
      Pattern.compile("@?([A-Za-z0-9_]*)//([^:]*)(:([^:/]+))?");

  /**
   * Reconstruct back to original path specified in config with slash separating the last path
   * component.
   */
  public String reconstructWithSlash() {
    return getCellName() + "//" + getCellRelPath();
  }

  /**
   * Reconstruct back to original path specified in config with colon separating the last path
   * component.
   */
  public String reconstructWithColon() {
    return getCellName()
        + "//"
        + getCellRelPath().getParentButEmptyForSingleSegment()
        + ":"
        + getCellRelPath().getFileName().orElseThrow(() -> new IllegalStateException("empty path"));
  }

  /**
   * Reconstruct back to original path specified in config with colon separating the last path
   * component and with colon for cell name.
   */
  public String reconstructWithAtAndColon() {
    return (getCellName().isEmpty() ? "" : "@") + reconstructWithColon();
  }

  @Override
  public String toString() {
    return reconstructWithSlash();
  }

  /** Parse implicit include path. */
  public static ImplicitIncludePath parse(String rawLabel, String configurationString)
      throws HumanReadableException {
    Matcher matcher = INCLUDE_PATH_PATTERN.matcher(rawLabel);
    if (!matcher.matches()) {
      throw parseException(configurationString);
    }

    String cellName = matcher.group(1);
    String path0 = matcher.group(2);
    String path1 = matcher.group(4);

    ForwardRelPath path = ForwardRelPath.of(path0);
    if (path1 != null) {
      path = path.resolve(path1);
    }

    if (path.isEmpty()) {
      throw parseException(configurationString);
    }

    return ImmutableImplicitIncludePath.ofImpl(cellName, path);
  }

  private static HumanReadableException parseException(String configurationString) {
    return new HumanReadableException(
        "Provided configuration %s specifies is incorrect, should be in form cell//path.bzl",
        configurationString);
  }

  /** Parse implicit include path. */
  public static ImplicitIncludePath parse(String path) throws HumanReadableException {
    return parse(path, path);
  }
}
