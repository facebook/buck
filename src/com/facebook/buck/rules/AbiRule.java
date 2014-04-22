/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

/**
 * {@link BuildRule} that can avoid rebuilding itself when the ABI of its deps has not changed and
 * all properties of the rule other than its deps have not changed.
 */
public interface AbiRule {

  /**
   * Key for {@link OnDiskBuildInfo} to identify the ABI key for a build rule.
   */
  public static final String ABI_KEY_ON_DISK_METADATA = "ABI_KEY";

  /**
   * Returns a {@link Sha1HashCode} that represents the ABI of this rule's deps.
   */
  public Sha1HashCode getAbiKeyForDeps();
}
