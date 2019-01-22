/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android.support.exopackage;

import java.util.List;

/**
 * Register an instance of this callback to receive a notification when new classes are hotswapped
 * into the app
 *
 * @param moduleClasses a list of all the classes contained in the changed modules.
 */
public interface OnModulesChangedCallback {
  void onModulesChanged(List<String> moduleClasses);
}
