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

package com.android.common.utils;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Properties;

import static com.android.common.SdkConstants.DOT_WEBP;
import static com.android.common.SdkConstants.DOT_PNG;
import static com.android.common.SdkConstants.DOT_GIF;
import static com.android.common.SdkConstants.DOT_9PNG;
import static com.android.common.SdkConstants.DOT_JPEG;
import static com.android.common.SdkConstants.DOT_JPG;
import static com.android.common.SdkConstants.DOT_BMP;

/** Miscellaneous utilities used by the Android SDK tools */
public class SdkUtils {
    /**
     * Returns true if the given string ends with the given suffix, using a
     * case-insensitive comparison.
     *
     * @param string the full string to be checked
     * @param suffix the suffix to be checked for
     * @return true if the string case-insensitively ends with the given suffix
     */
    public static boolean endsWithIgnoreCase(@NonNull String string, @NonNull String suffix) {
        return string.regionMatches(true /* ignoreCase */, string.length() - suffix.length(),
                suffix, 0, suffix.length());
    }

    /**
     * Returns true if the given sequence ends with the given suffix (case
     * sensitive).
     *
     * @param sequence the character sequence to be checked
     * @param suffix the suffix to look for
     * @return true if the given sequence ends with the given suffix
     */
    public static boolean endsWith(@NonNull CharSequence sequence, @NonNull CharSequence suffix) {
        return endsWith(sequence, sequence.length(), suffix);
    }

    /**
     * Returns true if the given sequence ends at the given offset with the given suffix (case
     * sensitive)
     *
     * @param sequence the character sequence to be checked
     * @param endOffset the offset at which the sequence is considered to end
     * @param suffix the suffix to look for
     * @return true if the given sequence ends with the given suffix
     */
    public static boolean endsWith(@NonNull CharSequence sequence, int endOffset,
            @NonNull CharSequence suffix) {
        if (endOffset < suffix.length()) {
            return false;
        }

        for (int i = endOffset - 1, j = suffix.length() - 1; j >= 0; i--, j--) {
            if (sequence.charAt(i) != suffix.charAt(j)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns true if the given string starts with the given prefix, using a
     * case-insensitive comparison.
     *
     * @param string the full string to be checked
     * @param prefix the prefix to be checked for
     * @return true if the string case-insensitively starts with the given prefix
     */
    public static boolean startsWithIgnoreCase(@NonNull String string, @NonNull String prefix) {
        return string.regionMatches(true /* ignoreCase */, 0, prefix, 0, prefix.length());
    }

    /** For use by {@link #getLineSeparator()} */
    private static String sLineSeparator;

    /**
     * Returns the default line separator to use.
     * <p>
     * NOTE: If you have an associated IDocument (Eclipse), it is better to call
     * TextUtilities#getDefaultLineDelimiter(IDocument) since that will
     * allow (for example) editing a \r\n-delimited document on a \n-delimited
     * platform and keep a consistent usage of delimiters in the file.
     *
     * @return the delimiter string to use
     */
    @NonNull
    public static String getLineSeparator() {
        if (sLineSeparator == null) {
            // This is guaranteed to exist:
            sLineSeparator = System.getProperty("line.separator"); //$NON-NLS-1$
        }

        return sLineSeparator;
    }

    /**
     * Wraps the given text at the given line width, with an optional hanging
     * indent.
     *
     * @param text the text to be wrapped
     * @param lineWidth the number of characters to wrap the text to
     * @param hangingIndent the hanging indent (to be used for the second and
     *            subsequent lines in each paragraph, or null if not known
     * @return the string, wrapped
     */
    @NonNull
    public static String wrap(
            @NonNull String text,
            int lineWidth,
            @Nullable String hangingIndent) {
        if (hangingIndent == null) {
            hangingIndent = "";
        }
        int explanationLength = text.length();
        StringBuilder sb = new StringBuilder(explanationLength * 2);
        int index = 0;

        while (index < explanationLength) {
            int lineEnd = text.indexOf('\n', index);
            int next;

            if (lineEnd != -1 && (lineEnd - index) < lineWidth) {
                next = lineEnd + 1;
            } else {
                // Line is longer than available width; grab as much as we can
                lineEnd = Math.min(index + lineWidth, explanationLength);
                if (lineEnd - index < lineWidth) {
                    next = explanationLength;
                } else {
                    // then back up to the last space
                    int lastSpace = text.lastIndexOf(' ', lineEnd);
                    if (lastSpace > index) {
                        lineEnd = lastSpace;
                        next = lastSpace + 1;
                    } else {
                        // No space anywhere on the line: it contains something wider than
                        // can fit (like a long URL) so just hard break it
                        next = lineEnd + 1;
                    }
                }
            }

            if (sb.length() > 0) {
                sb.append(hangingIndent);
            } else {
                lineWidth -= hangingIndent.length();
            }

            sb.append(text.substring(index, lineEnd));
            sb.append('\n');
            index = next;
        }

        return sb.toString();
    }

    /**
     * Returns the corresponding {@link File} for the given file:// url
     *
     * @param url the URL string, e.g. file://foo/bar
     * @return the corresponding {@link File} (which may or may not exist)
     * @throws MalformedURLException if the URL string is malformed or is not a file: URL
     */
    @NonNull
    public static File urlToFile(@NonNull String url) throws MalformedURLException {
        return urlToFile(new URL(url));
    }

    @NonNull
    public static File urlToFile(@NonNull URL url) throws MalformedURLException {
        try {
            return new File(url.toURI());
        }
        catch (IllegalArgumentException e) {
            MalformedURLException ex = new MalformedURLException(e.getLocalizedMessage());
            ex.initCause(e);
            throw ex;
        }
        catch (URISyntaxException e) {
            return new File(url.getPath());
        }
    }

    /**
     * Returns the corresponding URL string for the given {@link File}
     *
     * @param file the file to look up the URL for
     * @return the corresponding URL
     * @throws MalformedURLException in very unexpected cases
     */
    public static String fileToUrlString(@NonNull File file) throws MalformedURLException {
        String url = fileToUrl(file).toExternalForm();
        // Use three slashes, which is the form most widely recognized by terminal emulators.
        if (!url.startsWith("file:///")) {
            url = url.replaceFirst("file:/", "file:///");
        }
        return url;
    }

    /**
     * Returns the corresponding URL for the given {@link File}
     *
     * @param file the file to look up the URL for
     * @return the corresponding URL
     * @throws MalformedURLException in very unexpected cases
     */
    public static URL fileToUrl(@NonNull File file) throws MalformedURLException {
        return file.toURI().toURL();
    }

    /** Prefix in comments which mark the source locations for merge results */
    public static final String FILENAME_PREFIX = "From: ";

    /**
     * Creates the path comment XML string. Note that it does not escape characters
     * such as &amp; and &lt;; those are expected to be escaped by the caller (for
     * example, handled by a call to {@link org.w3c.dom.Document#createComment(String)})
     *
     *
     * @param file the file to create a path comment for
     * @param includePadding whether to include padding. The final comment recognized by
     *                       error recognizers expect padding between the {@code <!--} and
     *                       the start marker (From:); you can disable padding if the caller
     *                       already is in a context where the padding has been added.
     * @return the corresponding XML contents of the string
     */
    public static String createPathComment(@NonNull File file, boolean includePadding)
            throws MalformedURLException {
        String url = fileToUrlString(file);
        int dashes = url.indexOf("--");
        if (dashes != -1) { // Not allowed inside XML comments - for SGML compatibility. Sigh.
            url = url.replace("--", "%2D%2D");
        }

        if (includePadding) {
            return ' ' + FILENAME_PREFIX + url + ' ';
        } else {
            return FILENAME_PREFIX + url;
        }
    }

    /**
     * Translates an XML name (e.g. xml-name) into a Java / C++ constant name (e.g. XML_NAME)
     * @param xmlName the hyphen separated lower case xml name.
     * @return the equivalent constant name.
     */
    public static String xmlNameToConstantName(String xmlName) {
        return CaseFormat.LOWER_HYPHEN.to(CaseFormat.UPPER_UNDERSCORE, xmlName);
    }

    /**
     * Translates a camel case name (e.g. xmlName) into a Java / C++ constant name (e.g. XML_NAME)
     * @param camelCaseName the camel case name.
     * @return the equivalent constant name.
     */
    public static String camelCaseToConstantName(String camelCaseName) {
        return CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, camelCaseName);
    }

    /**
     * Translates a Java / C++ constant name (e.g. XML_NAME) into camel case name (e.g. xmlName)
     * @param constantName the constant name.
     * @return the equivalent camel case name.
     */
    public static String constantNameToCamelCase(String constantName) {
        return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, constantName);
    }

    /**
     * Translates a Java / C++ constant name (e.g. XML_NAME) into a XML case name (e.g. xml-name)
     * @param constantName the constant name.
     * @return the equivalent XML name.
     */
    public static String constantNameToXmlName(String constantName) {
        return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, constantName);
    }


    /**
     * Get the R field name from a resource name, since
     * AAPT will flatten the namespace, turning dots, dashes and colons into _
     *
     * @param resourceName the name to convert
     * @return the corresponding R field name
     */
    @NonNull
    public static String getResourceFieldName(@NonNull String resourceName) {
        // AAPT will flatten the namespace, turning dots, dashes and colons into _
        for (int i = 0, n = resourceName.length(); i < n; i++) {
            char c = resourceName.charAt(i);
            if (c == '.' || c == ':' || c == '-') {
                return resourceName.replace('.', '_').replace('-', '_').replace(':', '_');
            }
        }

        return resourceName;
    }

    public static final List<String> IMAGE_EXTENSIONS = ImmutableList.of(
            DOT_PNG, DOT_9PNG, DOT_GIF, DOT_JPEG, DOT_JPG, DOT_BMP, DOT_WEBP);

    /**
     * Returns true if the given file path points to an image file recognized by
     * Android. See http://developer.android.com/guide/appendix/media-formats.html
     * for details.
     *
     * @param path the filename to be tested
     * @return true if the file represents an image file
     */
    public static boolean hasImageExtension(String path) {
        for (String ext: IMAGE_EXTENSIONS) {
            if (endsWithIgnoreCase(path, ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Escapes the given property file value (right hand side of property assignment)
     * as required by the property file format (e.g. escapes colons and backslashes)
     *
     * @param value the value to be escaped
     * @return the escaped value
     */
    @NonNull
    public static String escapePropertyValue(@NonNull String value) {
        // Slow, stupid implementation, but is 100% compatible with Java's property file
        // implementation
        Properties properties = new Properties();
        properties.setProperty("k", value); // key doesn't matter
        StringWriter writer = new StringWriter();
        try {
            properties.store(writer, null);
            String s = writer.toString();
            int end = s.length();

            // Writer inserts trailing newline
            String lineSeparator = SdkUtils.getLineSeparator();
            if (s.endsWith(lineSeparator)) {
                end -= lineSeparator.length();
            }

            int start = s.indexOf('=');
            assert start != -1 : s;
            return s.substring(start + 1, end);
        }
        catch (IOException e) {
            return value; // shouldn't happen; we're not going to disk
        }
    }
}
