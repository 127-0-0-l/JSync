package org.steel;

public record DiskTask (
    DiskTaskType taskType,
    String sourcePath,
    String destinationPath,
    boolean isFile
) {}
