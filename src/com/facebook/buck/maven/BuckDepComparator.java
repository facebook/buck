/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.maven;

import com.google.common.base.Preconditions;

import java.util.Comparator;

/**
 * Order dependencies in the same way that Buck expects: ":target", "//alpha:other".
 */
class BuckDepComparator implements Comparator<String> {

  @Override
  public int compare(String left, String right) {
    Preconditions.checkState(left.length() > 1);
    Preconditions.checkState(right.length() > 1);

    int lcolon = left.indexOf(':');
    int rcolon = right.indexOf(':');

    Preconditions.checkState(lcolon != -1);
    Preconditions.checkState(rcolon != -1);

    String lpath = left.substring(0, lcolon);
    String lname = left.substring(lcolon);

    String rpath = right.substring(0, rcolon);
    String rname = right.substring(rcolon);

    int pathComparison = lpath.compareTo(rpath);
    if (pathComparison == 0) {
      return lname.compareTo(rname);
    }
    return pathComparison;
  }
}
