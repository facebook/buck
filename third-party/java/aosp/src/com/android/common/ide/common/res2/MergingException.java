/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.common.ide.common.res2;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.common.ide.common.blame.Message;
import com.android.common.ide.common.blame.Message.Kind;
import com.android.common.ide.common.blame.SourceFile;
import com.android.common.ide.common.blame.SourceFilePosition;
import com.android.common.ide.common.blame.SourcePosition;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.xml.sax.SAXParseException;

import java.io.File;
import java.util.Collection;
import java.util.List;

/**
 * Exception for errors during merging.
 */
public class MergingException extends Exception {

    public static final String MULTIPLE_ERRORS = "Multiple errors:";

    public static final String RESOURCE_ASSET_MERGER_TOOL_NAME = "Resource and asset merger";

    @NonNull
    private final List<Message> mMessages;

    /**
     * For internal use. Creates a new MergingException
     *
     * @param cause    the original exception. May be null.
     * @param messages the messaged. Must contain at least one item.
     */
    protected MergingException(@Nullable Throwable cause, @NonNull Message... messages) {
        super(messages.length == 1 ? messages[0].getText() : MULTIPLE_ERRORS, cause);
        mMessages = ImmutableList.copyOf(messages);
    }

    public static class Builder {

        @Nullable
        private Throwable mCause = null;

        @Nullable
        private String mMessageText = null;

        @Nullable
        private String mOriginalMessageText = null;

        @NonNull
        private SourceFile mFile = SourceFile.UNKNOWN;

        @NonNull
        private SourcePosition mPosition = SourcePosition.UNKNOWN;

        private Builder() {
        }

        public Builder wrapException(@NonNull Throwable cause) {
            mCause = cause;
            mOriginalMessageText = Throwables.getStackTraceAsString(cause);
            return this;
        }

        public Builder withFile(@NonNull File file) {
            mFile = new SourceFile(file);
            return this;
        }

        public Builder withFile(@NonNull SourceFile file) {
            mFile = file;
            return this;
        }

        public Builder withPosition(@NonNull SourcePosition position) {
            mPosition = position;
            return this;
        }

        public Builder withMessage(@NonNull String messageText, Object... args) {
            mMessageText = args.length == 0 ? messageText : String.format(messageText, args);
            return this;
        }

        public MergingException build() {
            if (mCause != null) {
                if (mMessageText == null) {
                    mMessageText = MoreObjects.firstNonNull(
                            mCause.getLocalizedMessage(), mCause.getClass().getCanonicalName());
                }
                if (mPosition == SourcePosition.UNKNOWN && mCause instanceof SAXParseException) {
                    SAXParseException exception = (SAXParseException) mCause;
                    int lineNumber = exception.getLineNumber();
                    if (lineNumber != -1) {
                        // Convert positions to be 0-based for SourceFilePosition.
                        mPosition = new SourcePosition(lineNumber - 1,
                                exception.getColumnNumber() - 1, -1);
                    }
                }
            }

            if (mMessageText == null) {
                mMessageText = "Unknown error.";
            }

            return new MergingException(
                    mCause,
                    new Message(
                            Kind.ERROR,
                            mMessageText,
                            MoreObjects.firstNonNull(mOriginalMessageText, mMessageText),
                            RESOURCE_ASSET_MERGER_TOOL_NAME,
                            new SourceFilePosition(mFile, mPosition)));
        }

    }

    public static Builder wrapException(@NonNull Throwable cause) {
        return new Builder().wrapException(cause);
    }

    public static Builder withMessage(@NonNull String message, Object... args) {
        return new Builder().withMessage(message, args);
    }


    public static void throwIfNonEmpty(Collection<Message> messages) throws MergingException {
        if (!messages.isEmpty()) {
            throw new MergingException(null, Iterables.toArray(messages, Message.class));
        }
    }

    @NonNull
    public List<Message> getMessages() {
        return mMessages;
    }

    /**
     * Computes the error message to display for this error
     */
    @NonNull
    @Override
    public String getMessage() {
        List<String> messages = Lists.newArrayListWithCapacity(mMessages.size());
        for (Message message : mMessages) {
            StringBuilder sb = new StringBuilder();
            List<SourceFilePosition> sourceFilePositions = message.getSourceFilePositions();
            if (sourceFilePositions.size() > 1 || !sourceFilePositions.get(0)
                    .equals(SourceFilePosition.UNKNOWN)) {
                sb.append(Joiner.on('\t').join(sourceFilePositions));
            }

            String text = message.getText();
            if (sb.length() > 0) {
                sb.append(':').append(' ');

                // ALWAYS insert the string "Error:" between the path and the message.
                // This is done to make the error messages more simple to detect
                // (since a generic path: message pattern can match a lot of output, basically
                // any labeled output, and we don't want to do file existence checks on any random
                // string to the left of a colon.)
                if (!text.startsWith("Error: ")) {
                    sb.append("Error: ");
                }
            } else if (!text.contains("Error: ")) {
                sb.append("Error: ");
            }

            // If the error message already starts with the path, strip it out.
            // This avoids redundant looking error messages you can end up with
            // like for example for permission denied errors where the error message
            // string itself contains the path as a prefix:
            //    /my/full/path: /my/full/path (Permission denied)
            if (sourceFilePositions.size() == 1) {
                File file = sourceFilePositions.get(0).getFile().getSourceFile();
                if (file != null) {
                    String path = file.getAbsolutePath();
                    if (text.startsWith(path)) {
                        int stripStart = path.length();
                        if (text.length() > stripStart && text.charAt(stripStart) == ':') {
                            stripStart++;
                        }
                        if (text.length() > stripStart && text.charAt(stripStart) == ' ') {
                            stripStart++;
                        }
                        text = text.substring(stripStart);
                    }
                }
            }

            sb.append(text);
            messages.add(sb.toString());
        }
        return Joiner.on('\n').join(messages);
    }

    @Override
    public String toString() {
        return getMessage();
    }
}
