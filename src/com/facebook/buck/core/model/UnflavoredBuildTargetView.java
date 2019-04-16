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

package com.facebook.buck.core.model;

import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A build target in the form of
 *
 * <pre>cell//path:rule</pre>
 *
 * .
 *
 * <p>If used as data class, consider {@link UnconfiguredBuildTarget} instead
 */
@ThreadSafe
public interface UnflavoredBuildTargetView extends Comparable<UnflavoredBuildTargetView> {

  String BUILD_TARGET_PREFIX = "//";

  // TODO: remove cell root path from this object. Don't forget to remove TODOs from
  // BuildTargetMacro after that
  Path getCellPath();

  Optional<String> getCell();

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava".
   */
  String getBaseName();

  String getShortName();

  /**
   * If this build target is //third_party/java/guava:guava-latest, then this would return
   * "//third_party/java/guava:guava-latest".
   */
  String getFullyQualifiedName();

  /**
   * If this build target were //third_party/java/guava:guava-latest, then this would return the
   * {@link Path} "third_party/java/guava". This does not contain the "//" prefix so that it can be
   * appended to a file path.
   */
  Path getBasePath();

  /** Data object that backs current instance */
  UnconfiguredBuildTarget getData();
}
