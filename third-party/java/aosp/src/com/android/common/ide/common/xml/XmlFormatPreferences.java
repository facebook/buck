/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.common.ide.common.xml;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.VisibleForTesting;

import org.w3c.dom.Attr;

import java.util.Comparator;

/**
 * Formatting preferences used by the Android XML formatter.
 */
public class XmlFormatPreferences {
    /** Use the Eclipse indent (tab/space, indent size) settings? */
    public boolean useEclipseIndent = false;

    /** Remove empty lines in all cases? */
    public boolean removeEmptyLines = false;

    /** Reformat the text and comment blocks? */
    public boolean reflowText = false;

    /** Join lines when reformatting text and comment blocks? */
    public boolean joinLines = false;

    /** Can attributes appear on the same line as the opening line if there is just one of them? */
    public boolean oneAttributeOnFirstLine = true;

    /** The sorting order to use when formatting */
    public XmlAttributeSortOrder sortAttributes = XmlAttributeSortOrder.LOGICAL;

    /** Returns the comparator to use when formatting, or null for no sorting */
    @Nullable
    public Comparator<Attr> getAttributeComparator() {
        return sortAttributes.getAttributeComparator();
    }

    /** Should there be a space before the closing {@code >}; or {@code >/;} ? */
    public boolean spaceBeforeClose = true;

    /** The string to insert for each indentation level */
    protected String mOneIndentUnit = "    "; //$NON-NLS-1$

    /** Tab width (number of spaces to display for a tab) */
    protected int mTabWidth = -1; // -1: uninitialized

    @VisibleForTesting
    protected XmlFormatPreferences() {
    }

    /**
     * Returns a new preferences object initialized with the defaults
     *
     * @return an {@link XmlFormatPreferences} object
     */
    @NonNull
    public static XmlFormatPreferences defaults() {
        return new XmlFormatPreferences();
    }

    public String getOneIndentUnit() {
        return mOneIndentUnit;
    }

    /**
     * Returns the number of spaces used to display a single tab character
     *
     * @return the number of spaces used to display a single tab character
     */
    @SuppressWarnings("restriction") // Editor settings
    public int getTabWidth() {
        if (mTabWidth == -1) {
            mTabWidth = 4;
        }

        return mTabWidth;
    }
}
