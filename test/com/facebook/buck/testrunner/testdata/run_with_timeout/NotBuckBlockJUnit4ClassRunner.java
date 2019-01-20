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

package com.example;

import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;

/**
 * A nominal subclass of {@link BlockJUnit4ClassRunner} that can be used with {@link RunWith}. This
 * is a good test case because {@code org.robolectric.RobolectricTestRunner} is also a subclass of
 * {@link BlockJUnit4ClassRunner}.
 */
public class NotBuckBlockJUnit4ClassRunner extends BlockJUnit4ClassRunner {

  public NotBuckBlockJUnit4ClassRunner(Class<?> klass) throws InitializationError {
    super(klass);
  }
}
