/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;

public interface HasJavaAbi extends HasBuildTarget {

  /**
   * Returns a SHA-1 hash that represents the ABI for the Java files that are compiled by this rule.
   * The only requirement on the hash is that equal hashes imply equal ABIs.
   * <p>
   * Because the ABI is computed as part of the build process, this rule cannot be invoked until
   * after this rule is built.
   */
  Sha1HashCode getAbiKey();

  /**
   * @return the {@link SourcePath} representing the ABI Jar for this rule.
   */
  Optional<SourcePath> getAbiJar();

}
