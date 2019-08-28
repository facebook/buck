/*
 * Copyright 2019-present Facebook, Inc.
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
@file:Suppress("MatchingDeclarationName")
package com.facebook.buck.multitenant.runner

import com.facebook.buck.multitenant.service.populateIndexFromStream
import com.facebook.buck.multitenant.service.IndexComponents
import com.facebook.buck.multitenant.service.IndexFactory
import java.io.InputStream

/**
 * Creates a map with a single Index based on the data from the specified file.
 */
fun createIndex(stream: InputStream): IndexComponents {
    val (index, appender) = IndexFactory.createIndex()
    populateIndexFromStream(appender, stream)

    return IndexComponents(index, appender, mapOf("" to "BUCK"))
}
