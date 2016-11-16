/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.common.ide.common.blame;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.List;

@Immutable
public final class Message {

    @NonNull
    private final Kind mKind;

    @NonNull
    private final String mText;

    @NonNull
    private final List<SourceFilePosition> mSourceFilePositions;

    @NonNull
    private final String mRawMessage;

    @NonNull
    private final Optional<String> mToolName;

    /**
     * Create a new message, which has a {@link Kind}, a String which will be shown to the user and
     * at least one {@link SourceFilePosition}.
     *
     * @param kind the message type.
     * @param text the text of the message.
     * @param sourceFilePosition the first source file position the message .
     * @param sourceFilePositions any additional source file positions, may be empty.
     */
    public Message(@NonNull Kind kind,
            @NonNull String text,
            @NonNull SourceFilePosition sourceFilePosition,
            @NonNull SourceFilePosition... sourceFilePositions) {
        mKind = kind;
        mText = text;
        mRawMessage = text;
        mSourceFilePositions = ImmutableList.<SourceFilePosition>builder()
                .add(sourceFilePosition).add(sourceFilePositions).build();
        mToolName = Optional.absent();
    }

    /**
     * Create a new message, which has a {@link Kind}, a String which will be shown to the user and
     * at least one {@link SourceFilePosition}.
     *
     * It also has a rawMessage, to store the original string for cases when the message is
     * constructed by parsing the output from another tool.
     *
     * @param kind the message kind.
     * @param text a human-readable string explaining the issue.
     * @param rawMessage the original text of the message, usually from an external tool.
     * @param toolName the name of the tool that produced the message, e.g. AAPT.
     * @param sourceFilePosition the first source file position.
     * @param sourceFilePositions any additional source file positions, may be empty.
     */
    public Message(@NonNull Kind kind,
            @NonNull String text,
            @NonNull String rawMessage,
            @Nullable String toolName,
            @NonNull SourceFilePosition sourceFilePosition,
            @NonNull SourceFilePosition... sourceFilePositions) {
        mKind = kind;
        mText = text;
        mRawMessage = rawMessage;
        mToolName = Optional.fromNullable(toolName);
        mSourceFilePositions = ImmutableList.<SourceFilePosition>builder()
                .add(sourceFilePosition).add(sourceFilePositions).build();
    }

    public Message(@NonNull Kind kind,
            @NonNull String text,
            @NonNull String rawMessage,
            @NonNull Optional<String> toolName,
            @NonNull ImmutableList<SourceFilePosition> positions) {
        mKind = kind;
        mText = text;
        mRawMessage = rawMessage;
        mToolName = toolName;

        if (positions.isEmpty()) {
            mSourceFilePositions = ImmutableList.of(SourceFilePosition.UNKNOWN);
        } else {
            mSourceFilePositions = positions;
        }
    }

    @NonNull
    public Kind getKind() {
        return mKind;
    }

    @NonNull
    public String getText() {
        return mText;
    }

    /**
     * Returns a list of source positions. Will always contain at least one item.
     */
    @NonNull
    public List<SourceFilePosition> getSourceFilePositions() {
        return mSourceFilePositions;
    }

    @NonNull
    public String getRawMessage() {
        return mRawMessage;
    }

    @NonNull
    public Optional<String> getToolName() {
        return mToolName;
    }

    @Nullable
    public String getSourcePath() {
        File file = mSourceFilePositions.get(0).getFile().getSourceFile();
        if (file == null) {
            return null;
        }
        return file.getAbsolutePath();
    }

    /**
     * Returns a legacy 1-based line number.
     */
    @Deprecated
    public int getLineNumber() {
        return mSourceFilePositions.get(0).getPosition().getStartLine() + 1;
    }

    /**
     * @return a legacy 1-based column number.
     */
    @Deprecated
    public int getColumn() {
        return mSourceFilePositions.get(0).getPosition().getStartColumn() + 1;
    }

    public enum Kind {
        ERROR, WARNING, INFO, STATISTICS, UNKNOWN, SIMPLE;

        public static Kind findIgnoringCase(String s, Kind defaultKind) {
            for (Kind kind : values()) {
                if (kind.toString().equalsIgnoreCase(s)) {
                    return kind;
                }
            }
            return defaultKind;
        }

        @Nullable
        public static Kind findIgnoringCase(String s) {
            return findIgnoringCase(s, null);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Message)) {
            return false;
        }
        Message that = (Message) o;
        return Objects.equal(mKind, that.mKind) &&
                Objects.equal(mText, that.mText) &&
                Objects.equal(mRawMessage, that.mRawMessage) &&
                Objects.equal(mToolName, that.mToolName) &&
                Objects.equal(mSourceFilePositions, that.mSourceFilePositions);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mKind, mText, mSourceFilePositions);
    }

    @Override
    public String toString() {
        Objects.ToStringHelper toStringHelper =
                Objects.toStringHelper(this).add("kind", mKind).add("text", mText)
                .add("sources", mSourceFilePositions);
        if (!mText.equals(mRawMessage)) {
            toStringHelper.add("original message", mRawMessage);
        }
        if (mToolName.isPresent()) {
            toStringHelper.add("tool name", mToolName);
        }
        return toStringHelper.toString();
    }
}
