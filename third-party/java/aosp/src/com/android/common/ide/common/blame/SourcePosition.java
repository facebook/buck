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
import com.android.annotations.concurrency.Immutable;
import com.google.common.base.Objects;

/**
 * An immutable position in a text file, used in errors to point the user to an issue.
 *
 * Positions that are unknown are represented by -1.
 */
@Immutable
public final class SourcePosition {

    @NonNull
    public static final SourcePosition UNKNOWN = new SourcePosition();

    private final int mStartLine, mStartColumn, mStartOffset, mEndLine, mEndColumn, mEndOffset;

    public SourcePosition(int startLine, int startColumn, int startOffset,
            int endLine, int endColumn, int endOffset) {
        mStartLine = startLine;
        mStartColumn = startColumn;
        mStartOffset = startOffset;
        mEndLine = endLine;
        mEndColumn = endColumn;
        mEndOffset = endOffset;
    }

    public SourcePosition(int lineNumber, int column, int offset) {
        mStartLine = mEndLine = lineNumber;
        mStartColumn = mEndColumn = column;
        mStartOffset = mEndOffset = offset;
    }

    private SourcePosition() {
        mStartLine = mStartColumn = mStartOffset = mEndLine = mEndColumn = mEndOffset = -1;
    }

    protected SourcePosition(SourcePosition copy) {
        mStartLine = copy.getStartLine();
        mStartColumn = copy.getStartColumn();
        mStartOffset = copy.getStartOffset();
        mEndLine = copy.getEndLine();
        mEndColumn = copy.getEndColumn();
        mEndOffset = copy.getEndOffset();
    }

    /**
     * Outputs positions as human-readable formatted strings.
     *
     * e.g.
     * <pre>84
     * 84-86
     * 84:5
     * 84:5-28
     * 85:5-86:47</pre>
     *
     * @return a human readable position.
     */
    @Override
    public String toString() {
        if (mStartLine == -1) {
            return "?";
        }
        StringBuilder sB = new StringBuilder(15);
        sB.append(mStartLine + 1); // Humans think that the first line is line 1.
        if (mStartColumn != -1) {
            sB.append(':');
            sB.append(mStartColumn + 1);
        }
        if (mEndLine != -1) {

            if (mEndLine == mStartLine) {
                if (mEndColumn != -1 && mEndColumn != mStartColumn) {
                    sB.append('-');
                    sB.append(mEndColumn + 1);
                }
            } else {
                sB.append('-');
                sB.append(mEndLine + 1);
                if (mEndColumn != -1) {
                    sB.append(':');
                    sB.append(mEndColumn + 1);
                }
            }
        }
        return sB.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SourcePosition)) {
            return false;
        }
        SourcePosition other = (SourcePosition) obj;

        return other.mStartLine == mStartLine &&
                other.mStartColumn == mStartColumn &&
                other.mStartOffset == mStartOffset &&
                other.mEndLine == mEndLine &&
                other.mEndColumn == mEndColumn &&
                other.mEndOffset == mEndOffset;
    }

    @Override
    public int hashCode() {
        return Objects
                .hashCode(mStartLine, mStartColumn, mStartOffset, mEndLine, mEndColumn, mEndOffset);
    }

    public int getStartLine() {
        return mStartLine;
    }

    public int getStartColumn() {
        return mStartColumn;
    }

    public int getStartOffset() {
        return mStartOffset;
    }


    public int getEndLine() {
        return mEndLine;
    }

    public int getEndColumn() {
        return mEndColumn;
    }

    public int getEndOffset() {
        return mEndOffset;
    }

    /**
     * Compares the start of this SourcePosition with another.
     * @return 0 if they are the same, &lt; 0 if this &lt; other and &gt; 0 if this &gt; other
     */
    public int compareStart(@NonNull SourcePosition other) {
        if (mStartOffset != -1 && other.mStartOffset != -1) {
            return mStartOffset - other.mStartOffset;
        }
        if (mStartLine == other.mStartLine) {
            return mStartColumn - other.mStartColumn;
        }
        return mStartLine - other.mStartLine;
    }

    /**
     * Compares the end of this SourcePosition with another.
     * @return 0 if they are the same, &lt; 0 if this &lt; other and &gt; 0 if this &gt; other
     */
    public int compareEnd(@NonNull SourcePosition other) {
        if (mEndOffset != -1 && other.mEndOffset != -1) {
            return mEndOffset - other.mEndOffset;
        }
        if (mEndLine == other.mEndLine) {
            return mEndColumn - other.mEndColumn;
        }
        return mEndLine - other.mEndLine;
    }
}
