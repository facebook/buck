// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking;

import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.shaking.ProguardTypeMatcher.MatchSpecificType;
import com.google.common.collect.ImmutableList;
import java.util.Set;

public class ProguardClassFilter {
  private final ImmutableList<ProguardClassNameList> patterns;

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private final ImmutableList.Builder<ProguardClassNameList> patterns = ImmutableList.builder();

    private Builder() {
    }

    public Builder addPattern(ProguardClassNameList pattern) {
      patterns.add(pattern);
      return this;
    }

    ProguardClassFilter build() {
      return new ProguardClassFilter(patterns.build());
    }
  }

  private ProguardClassFilter(ImmutableList<ProguardClassNameList> patterns) {
    this.patterns = patterns;
  }

  public boolean isEmpty() {
    return patterns.size() == 0;
  }

  public boolean matches(DexType type) {
    for (ProguardClassNameList pattern : patterns) {
      if (pattern.matches(type)) {
        return true;
      }
    }
    return false;
  }

  public void filterOutMatches(Set<DexType> types) {
    for (ProguardClassNameList pattern : patterns) {
      pattern.forEachTypeMatcher(matcher -> {
        if (matcher instanceof MatchSpecificType) {
          assert matcher.getSpecificType() != null;
          types.remove(matcher.getSpecificType());
        } else {
          types.removeIf(matcher::matches);
        }
      });
    }
  }
}
