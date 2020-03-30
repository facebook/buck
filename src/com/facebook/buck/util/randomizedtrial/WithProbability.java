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

package com.facebook.buck.util.randomizedtrial;

/**
 * Marks an enum for toggling buck experiments as having probability. This means that each
 * itemization of the enum can be attached with a specific probability of it being selected by a
 * random trial.
 */
public interface WithProbability {

  /**
   * @return the probability of an item of the enum being selected. This should sum up to 1.0 for
   *     all items of an enum.
   */
  double getProbability();
}
