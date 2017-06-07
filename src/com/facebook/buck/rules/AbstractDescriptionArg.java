/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import org.immutables.value.Value;

/**
 * THIS IS A LEGACY CLASS WHICH IS ABOUT TO BE DELETED.
 *
 * <p>DO NOT ADD ANY NEW BEHAVIOR TO IT, OR NEW REFERENCES TO IT.
 *
 * <p>YOUR NEW BEHAVIOR PROBABLY WANTS TO LIVE ON CommonArg.
 */
@SuppressFieldNotInitialized
public abstract class AbstractDescriptionArg implements CommonDescriptionArg {
  public ImmutableSet<SourcePath> licenses = ImmutableSet.of();
  public ImmutableSortedSet<String> labels = ImmutableSortedSet.of();

  @Override
  public String getName() {
    throw new UnsupportedOperationException("Legacy Descriptions don't support getName");
  }

  @Override
  public ImmutableSet<SourcePath> getLicenses() {
    return licenses;
  }

  @Value.NaturalOrder
  @Override
  public ImmutableSortedSet<String> getLabels() {
    return labels;
  }
}
