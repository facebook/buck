// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

// Helper class implementing bunch of default interface method handling operations.
final class DefaultMethodsHelper {
  // Current set of default interface methods, may overlap with `hidden`.
  private final Set<DexEncodedMethod> candidates = Sets.newIdentityHashSet();
  // Current set of known hidden default interface methods.
  private final Set<DexEncodedMethod> hidden = Sets.newIdentityHashSet();

  // Represents information about default interface methods of an
  // interface and its superinterfaces. Namely: a list of live (not hidden)
  // and hidden default interface methods in this interface's hierarchy.
  //
  // Note that it is assumes that these lists should never be big.
  final static class Collection {
    static final Collection EMPTY =
        new Collection(Collections.emptyList(), Collections.emptyList());

    // All live default interface methods in this interface's hierarchy.
    private final List<DexEncodedMethod> live;
    // All hidden default interface methods in this interface's hierarchy.
    private final List<DexEncodedMethod> hidden;

    private Collection(List<DexEncodedMethod> live, List<DexEncodedMethod> hidden) {
      this.live = live;
      this.hidden = hidden;
    }
  }

  final void merge(Collection collection) {
    candidates.addAll(collection.live);
    hidden.addAll(collection.hidden);
  }

  final void hideMatches(DexMethod method) {
    Iterator<DexEncodedMethod> it = candidates.iterator();
    while (it.hasNext()) {
      DexEncodedMethod candidate = it.next();
      if (method.match(candidate)) {
        hidden.add(candidate);
        it.remove();
      }
    }
  }

  final void addDefaultMethod(DexEncodedMethod encoded) {
    candidates.add(encoded);
  }

  // Creates a list of default method candidates to be implemented in the class.
  final List<DexEncodedMethod> createCandidatesList() {
    this.candidates.removeAll(hidden);
    if (this.candidates.isEmpty()) {
      return Collections.emptyList();
    }

    // The list of non-hidden default methods. The list is not expected to be big,
    // since it only consists of default methods which are maximally specific
    // interface method of a particular class.
    List<DexEncodedMethod> candidates = new LinkedList<>();

    // Note that it is possible for a class to have more than one maximally specific
    // interface method. But runtime requires that when a method is called, there must be
    // found *only one* maximally specific interface method and this method should be
    // non-abstract, otherwise a runtime error is generated.
    //
    // This code assumes that if such erroneous case exist for particular name/signature,
    // a method with this name/signature must be defined in class or one of its superclasses,
    // or otherwise it should never be called. This means that if we see two default method
    // candidates with same name/signature, it is safe to assume that we don't need to add
    // these method to the class, because if it was missing in class and its superclasses
    // but still called in the original code, this call would have resulted in runtime error.
    // So we are just leaving it unimplemented with the same effect (with a different runtime
    // exception though).
    for (DexEncodedMethod candidate : this.candidates) {
      Iterator<DexEncodedMethod> it = candidates.iterator();
      boolean conflict = false;
      while (it.hasNext()) {
        if (candidate.method.match(it.next())) {
          conflict = true;
          it.remove();
        }
      }
      if (!conflict) {
        candidates.add(candidate);
      }
    }
    return candidates;
  }

  final List<DexEncodedMethod> createFullList() {
    if (candidates.isEmpty() && hidden.isEmpty()) {
      return Collections.emptyList();
    }

    List<DexEncodedMethod> fullList =
        new ArrayList<DexEncodedMethod>(candidates.size() + hidden.size());
    fullList.addAll(candidates);
    fullList.addAll(hidden);
    return fullList;
  }

  // Create default interface collection based on collected information.
  final Collection wrapInCollection() {
    candidates.removeAll(hidden);
    return (candidates.isEmpty() && hidden.isEmpty()) ? Collection.EMPTY
        : new Collection(Lists.newArrayList(candidates), Lists.newArrayList(hidden));
  }
}
