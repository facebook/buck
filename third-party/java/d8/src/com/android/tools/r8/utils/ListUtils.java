// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

public class ListUtils {

  public static <S, T> List<T> map(Collection<S> list, Function<S, T> fn) {
    List<T> result = new ArrayList<>(list.size());
    for (S element : list) {
      result.add(fn.apply(element));
    }
    return result;
  }
}
