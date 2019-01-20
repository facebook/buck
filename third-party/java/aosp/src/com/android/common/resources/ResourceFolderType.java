/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.common.resources;

/**
 * Enum representing a type of resource folder.
 */
public enum ResourceFolderType {
    ANIM(ResourceConstants.FD_RES_ANIM),
    ANIMATOR(ResourceConstants.FD_RES_ANIMATOR),
    COLOR(ResourceConstants.FD_RES_COLOR),
    DRAWABLE(ResourceConstants.FD_RES_DRAWABLE),
    INTERPOLATOR(ResourceConstants.FD_RES_INTERPOLATOR),
    LAYOUT(ResourceConstants.FD_RES_LAYOUT),
    MENU(ResourceConstants.FD_RES_MENU),
    MIPMAP(ResourceConstants.FD_RES_MIPMAP),
    RAW(ResourceConstants.FD_RES_RAW),
    TRANSITION(ResourceConstants.FD_RES_TRANSITION),
    VALUES(ResourceConstants.FD_RES_VALUES),
    XML(ResourceConstants.FD_RES_XML);

    private final String mName;

    ResourceFolderType(String name) {
        mName = name;
    }

    /**
     * Returns the folder name for this resource folder type.
     */
    public String getName() {
        return mName;
    }

    /**
     * Returns the enum by name.
     * @param name The enum string value.
     * @return the enum or null if not found.
     */
    public static ResourceFolderType getTypeByName(String name) {
        assert name.indexOf('-') == -1 : name; // use #getFolderType instead
        for (ResourceFolderType rType : values()) {
            if (rType.mName.equals(name)) {
                return rType;
            }
        }
        return null;
    }

    /**
     * Returns the {@link ResourceFolderType} from the folder name
     * @param folderName The name of the folder. This must be a valid folder name in the format
     * <code>resType[-resqualifiers[-resqualifiers[...]]</code>
     * @return the <code>ResourceFolderType</code> representing the type of the folder, or
     * <code>null</code> if no matching type was found.
     */
    public static ResourceFolderType getFolderType(String folderName) {
        int index = folderName.indexOf(ResourceConstants.RES_QUALIFIER_SEP);
        if (index != -1) {
            folderName = folderName.substring(0, index);
        }
        return getTypeByName(folderName);
    }
}
