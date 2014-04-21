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

package com.facebook.buck.model;

import com.google.common.base.Function;

import java.util.Comparator;

public interface HasBuildTarget {

  Function<HasBuildTarget, BuildTarget> TO_TARGET =
      new Function<HasBuildTarget, BuildTarget>() {
        @Override
        public BuildTarget apply(HasBuildTarget input) {
          return input.getBuildTarget();
        }
      };

  Comparator<HasBuildTarget> BUILD_TARGET_COMPARATOR =
      new Comparator<HasBuildTarget>() {
        @Override
        public int compare(HasBuildTarget a, HasBuildTarget b) {
          if (a == b) {
            return 0;
          }
          if (a == null) {
            return 1;
          }
          if (b == null) {
            return -1;
          }
          return a.getBuildTarget().compareTo(b.getBuildTarget());
        }
      };

  BuildTarget getBuildTarget();
}
