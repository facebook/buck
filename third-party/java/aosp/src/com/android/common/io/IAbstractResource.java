/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.common.io;

/**
 * Base representation of a file system resource.
 * <p>
 * This somewhat limited interface is designed to let classes use file-system resources, without
 * having the manually handle either the standard Java file or the Eclipse file API..
 */
public interface IAbstractResource {

    /**
     * Returns the name of the resource.
     */
    String getName();

    /**
     * Returns the OS path of the folder location (may be absolute).
     */
    String getOsLocation();

    /**
     * Returns the path of the resource.
     */
    String getPath();

    /**
     * Returns whether the resource actually exists.
     */
    boolean exists();

    /**
     * Returns the parent folder or null if there is no parent.
     */
    IAbstractFolder getParentFolder();

    /**
     * Deletes the resource.
     */
    boolean delete();
}
