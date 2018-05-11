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

package com.facebook.buck.file;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.net.URI;
import java.util.Optional;

/** Common arguments for descriptions that download http files */
interface HttpCommonDescriptionArg extends CommonDescriptionArg {
  String getSha256();

  ImmutableList<URI> getUrls();

  Optional<String> getOut();

  /** Helper functions that validate properties of {@link HttpCommonDescriptionArg} */
  class HttpCommonDescriptionArgHelpers {

    /**
     * Parses a sha256 from a string
     *
     * @param sha256 A hex representation of a sha256 hash
     * @param target The target to use in error messages
     * @return The hash
     * @throws HumanReadableException If the string could not be parsed, or if it's of the wrong
     *     length
     */
    static HashCode parseSha256(String sha256, BuildTarget target) throws HumanReadableException {
      HashCode sha256Code;
      try {
        sha256Code = HashCode.fromString(sha256);
      } catch (IllegalArgumentException e) {
        throw new HumanReadableException(
            e,
            "%s when parsing sha256 of %s",
            e.getMessage(),
            target.getUnflavoredBuildTarget().getFullyQualifiedName());
      }

      if (sha256Code.bits() != 256) {
        throw new HumanReadableException(
            "%s does not appear to be a sha256 hash. Expected 256 bits, got %s bits when parsing %s",
            sha256, sha256Code.bits(), target.getUnflavoredBuildTarget().getFullyQualifiedName());
      }
      return sha256Code;
    }

    /**
     * Validate that the uris provided meet common for our http arguments
     *
     * @param uris The uris to examine
     * @param target The target to use in error messages
     * @throws HumanReadableException If {@code uris} has invalid uris, or zero uris
     */
    static void validateUris(ImmutableList<URI> uris, BuildTarget target)
        throws HumanReadableException {
      if (uris.size() == 0) {
        throw new HumanReadableException(
            "At least one url must be provided for %s",
            target.getUnflavoredBuildTarget().getFullyQualifiedName());
      }
      for (URI uri : uris) {
        if (!uri.getScheme().equals("http")
            && !uri.getScheme().equals("https")
            && !uri.getScheme().equals("mvn")) {
          throw new HumanReadableException(
              "Unsupported protocol '%s' for url %s in %s. Must be http or https",
              uri.getScheme(),
              uri.toString(),
              target.getUnflavoredBuildTarget().getFullyQualifiedName());
        }
      }
    }
  }
}
