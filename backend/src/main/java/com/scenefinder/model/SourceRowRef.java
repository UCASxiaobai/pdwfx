package com.scenefinder.model;

public class SourceRowRef {

    private final String sourceFile;
    private final long rowIndex;

    public SourceRowRef(String sourceFile, long rowIndex) {
        this.sourceFile = sourceFile;
        this.rowIndex = rowIndex;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public long getRowIndex() {
        return rowIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SourceRowRef)) return false;
        SourceRowRef that = (SourceRowRef) o;
        return rowIndex == that.rowIndex
                && (sourceFile == null ? that.sourceFile == null : sourceFile.equals(that.sourceFile));
    }

    @Override
    public int hashCode() {
        int result = sourceFile != null ? sourceFile.hashCode() : 0;
        result = 31 * result + Long.hashCode(rowIndex);
        return result;
    }
}
