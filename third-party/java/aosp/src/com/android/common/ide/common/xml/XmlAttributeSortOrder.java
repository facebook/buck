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

import org.w3c.dom.Attr;

import java.util.Comparator;

import static com.android.common.SdkConstants.ATTR_COLOR;
import static com.android.common.SdkConstants.ATTR_ID;
import static com.android.common.SdkConstants.ATTR_LAYOUT_HEIGHT;
import static com.android.common.SdkConstants.ATTR_LAYOUT_RESOURCE_PREFIX;
import static com.android.common.SdkConstants.ATTR_LAYOUT_WIDTH;
import static com.android.common.SdkConstants.ATTR_NAME;
import static com.android.common.SdkConstants.ATTR_STYLE;
import static com.android.common.SdkConstants.XMLNS;
import static com.google.common.base.Strings.nullToEmpty;

/** Order to use when sorting attributes */
public enum XmlAttributeSortOrder {
    NO_SORTING("none"),     //$NON-NLS-1$
    ALPHABETICAL("alpha"),  //$NON-NLS-1$
    LOGICAL("logical");     //$NON-NLS-1$

    XmlAttributeSortOrder(String key) {
        this.key = key;
    }

    public final String key;

    /**
     * @return a comparator for use by this attribute sort order
     */
    @Nullable
    public Comparator<Attr> getAttributeComparator() {
        switch (this) {
            case NO_SORTING:
                return null;
            case ALPHABETICAL:
                return ALPHABETICAL_COMPARATOR;
            case LOGICAL:
            default:
                return SORTED_ORDER_COMPARATOR;
        }
    }

    /** Comparator which can be used to sort attributes in the coding style priority order */
    private static final Comparator<Attr> SORTED_ORDER_COMPARATOR = new Comparator<Attr>() {
        @Override
        public int compare(Attr attr1, Attr attr2) {
            // Namespace declarations should always go first
            String prefix1 = attr1.getPrefix();
            String prefix2 = attr2.getPrefix();
            if (XMLNS.equals(prefix1)) {
                if (XMLNS.equals(prefix2)) {
                    return 0;
                }
                return -1;
            } else if (XMLNS.equals(attr2.getPrefix())) {
                return 1;
            }

            // Sort by preferred attribute order
            String name1 = prefix1 != null ? attr1.getLocalName() : attr1.getName();
            String name2 = prefix2 != null ? attr2.getLocalName() : attr2.getName();
            return compareAttributes(prefix1, name1, prefix2, name2);
        }
    };

    /**
     * Comparator which can be used to sort attributes into alphabetical order (but xmlns
     * is always first)
     */
    private static final Comparator<Attr> ALPHABETICAL_COMPARATOR = new Comparator<Attr>() {
        @Override
        public int compare(Attr attr1, Attr attr2) {
            // Namespace declarations should always go first
            if (XMLNS.equals(attr1.getPrefix())) {
                if (XMLNS.equals(attr2.getPrefix())) {
                    return 0;
                }
                return -1;
            } else if (XMLNS.equals(attr2.getPrefix())) {
                return 1;
            }

            // Sort by name rather than local name to ensure we sort by namespaces first,
            // then by names.
            return attr1.getName().compareTo(attr2.getName());
        }
    };

    /**
     * Returns {@link Comparator} values for ordering attributes in the following
     * order:
     * <ul>
     *   <li> id
     *   <li> style
     *   <li> layout_width
     *   <li> layout_height
     *   <li> other layout params, sorted alphabetically
     *   <li> other attributes, sorted alphabetically
     * </ul>
     *
     * @param name1 the first attribute name to compare
     * @param name2 the second attribute name to compare
     * @return a negative number if name1 should be ordered before name2
     */
    public static int compareAttributes(String name1, String name2) {
        int priority1 = getAttributePriority(name1);
        int priority2 = getAttributePriority(name2);
        if (priority1 != priority2) {
            return priority1 - priority2;
        }

        // Sort remaining attributes alphabetically
        return name1.compareTo(name2);
    }

    /**
     * Returns {@link Comparator} values for ordering attributes in the following
     * order:
     * <ul>
     *   <li> id
     *   <li> style
     *   <li> layout_width
     *   <li> layout_height
     *   <li> other layout params, sorted alphabetically
     *   <li> other attributes, sorted alphabetically, first by namespace, then by name
     * </ul>
     * @param prefix1 the namespace prefix, if any, of {@code name1}
     * @param name1 the first attribute name to compare
     * @param prefix2  the namespace prefix, if any, of {@code name2}
     * @param name2 the second attribute name to compare
     * @return a negative number if name1 should be ordered before name2
     */
    public static int compareAttributes(
            @Nullable String prefix1, @NonNull String name1,
            @Nullable String prefix2, @NonNull String name2) {
        int priority1 = getAttributePriority(name1);
        int priority2 = getAttributePriority(name2);
        if (priority1 != priority2) {
            return priority1 - priority2;
        }

        int namespaceDelta = nullToEmpty(prefix1).compareTo(nullToEmpty(prefix2));
        if (namespaceDelta != 0) {
            return namespaceDelta;
        }

        // Sort remaining attributes alphabetically
        return name1.compareTo(name2);
    }


    /** Returns a sorting priority for the given attribute name */
    private static int getAttributePriority(String name) {
        if (ATTR_ID.equals(name)) {
            return 10;
        }

        if (ATTR_NAME.equals(name)) {
            return 15;
        }

        if (ATTR_STYLE.equals(name)) {
            return 20;
        }

        if (name.startsWith(ATTR_LAYOUT_RESOURCE_PREFIX)) {
            // Width and height are special cased because we (a) want width and height
            // before the other layout attributes, and (b) we want width to sort before height
            // even though it comes after it alphabetically.
            if (name.equals(ATTR_LAYOUT_WIDTH)) {
                return 30;
            }
            if (name.equals(ATTR_LAYOUT_HEIGHT)) {
                return 40;
            }

            return 50;
        }

        // "color" sorts to the end
        if (ATTR_COLOR.equals(name)) {
            return 100;
        }

        return 60;
    }
}