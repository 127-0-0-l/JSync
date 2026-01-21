package org.steel;

import org.tinylog.Logger;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

import static org.steel.ConsoleManager.*;

public class FileSynchronizer {
    private final Path sourcePath;
    private final Path destinationPath;
    private final Queue<DiskTask> diskTasks = new LinkedList<>();
    private final List<String> failedToDelete = new ArrayList<>();
    private final List<String> failedToCopy = new ArrayList<>();
    private final List<String> failedToScan = new ArrayList<>();
    private long commonSize = 0;

    public FileSynchronizer(String sourcePath, String destinationPath) {
        this.sourcePath = Path.of(sourcePath);
        this.destinationPath = Path.of(destinationPath);
    }

    public void synchronize() {
        if (!isPathsValid()) {
            String msg = String.format("invalid paths: (source: %s) (destination: %s)",
                    sourcePath.toString(), destinationPath.toString());
            Logger.error(msg);
            System.out.println(msg);
            return;
        }

        writeLine("scanning...");
        scanDirectories(sourcePath, destinationPath);

        long dirToDelete = diskTasks.stream()
                .filter(t -> !t.isFile() && t.taskType() == DiskTaskType.DELETE)
                .count();
        long filesToDelete = diskTasks.stream()
                .filter(t -> t.isFile() && t.taskType() == DiskTaskType.DELETE)
                .count();
        long filesToCopy = diskTasks.stream()
                .filter(t -> t.isFile() && t.taskType() == DiskTaskType.COPY)
                .count();

        rewriteLines(new String[] {
                        "scanning complete",
                        String.format("%s directories %s files to delete", dirToDelete, filesToDelete)
                });
        writeLine(String.format("\n%s filesToCopy", filesToCopy));

        writeLine("\nsyncing...\n\n");
        syncDirectories();

        if (!failedToScan.isEmpty()) {
            writeLine("\nfailed to scan:");
            for (var item : failedToScan)
                writeLine(item);
        }

        if (!failedToDelete.isEmpty()) {
            writeLine("\nfailed to delete:");
            for (var item : failedToDelete)
                writeLine(item);
        }

        if (!failedToCopy.isEmpty()) {
            writeLine("\nfailed to copy:");
            for (var item : failedToCopy)
                writeLine(item);
        }
    }

    private void scanDirectories(Path source, Path destination) {
        rewriteLines(new String[]{source.toString().replace(" ", "")});

        try {
            // delete directories
            for (var directory : getDirectories(destination)) {
                if (!Files.exists(Path.of(source.toString(), directory.getFileName().toString()))) {
                    enqueueDelete(directory.toString(), false);
                    commonSize++;
                }
            }

            // create directories
            for (var directory : getDirectories(source)) {
                Path destDirectory = Path.of(destination.toString(), directory.getFileName().toString());
                if (!Files.exists(destDirectory)) {
                    Files.createDirectory(destDirectory);
                }

                scanDirectories(directory, destDirectory);
            }

            // delete files
            for (var file : getFiles(destination)) {
                Path sourceFile = Path.of(source.toString(), file.getFileName().toString());
                if (!Files.exists(sourceFile)) {
                    enqueueDelete(file.toString(), true);
                    commonSize++;
                } else if (Files.getLastModifiedTime(sourceFile).toMillis() != Files.getLastModifiedTime(file).toMillis()) {
                    enqueueDelete(file.toString(), true);
                    commonSize++;
                    enqueueCopy(sourceFile.toString(), file.toString());
                    commonSize += Files.size(sourceFile);
                }
            }

            // copy files
            for (var file : getFiles(source)) {
                Path destFile = Path.of(destination.toString(), file.getFileName().toString());
                if (!Files.exists(destFile)) {
                    enqueueCopy(file.toString(), destFile.toString());
                    commonSize += Files.size(file);
                }
            }
        } catch (Exception e) {
            String scanStr = String.format("(source: %s) (destination: %s)", source, destination);
            String errMsg = "failed to scan directories: " + scanStr;
            failedToScan.add(scanStr);
            Logger.warn(errMsg);
        }
    }

    private List<Path> getDirectories(Path path) {
        try (Stream<Path> stream = Files.list(path)) {
            List<Path> subDirs = stream
                    .filter(Files::isDirectory)
                    .toList();
            if (subDirs.size() > 0)
                return subDirs;
        } catch (IOException e) {
            Logger.warn(e);
        }
        return new ArrayList<>();
    }

    private List<Path> getFiles(Path path) {
        try (Stream<Path> stream = Files.list(path)) {
            List<Path> files = stream
                    .filter(f -> !Files.isDirectory(f))
                    .toList();
            if (files.size() > 0)
                return files;
        } catch (IOException e) {
            Logger.warn(e);
        }
        return new ArrayList<>();
    }

    private void syncDirectories() {
        long processed = 0;

        int dtCount = diskTasks.toArray().length;
        for (int i = 0; i < dtCount; i++) {
            int percantage = (int) ((processed * 100) / dtCount);
            DiskTask item = diskTasks.poll();

            switch (item.taskType()) {
                case DiskTaskType.DELETE:
                    rewriteLinesWithProgress(
                            new String[]{String.format("delete %s", item.destinationPath()), getProgressInfoString(processed)},
                            percantage);

                    try {
                        if (item.isFile())
                            Files.deleteIfExists(Path.of(item.destinationPath()));
                        else
                            deleteDirectory(Path.of(item.destinationPath()));
                    } catch (Exception e) {
                        Logger.warn(e);
                        try {
                            forceDelete(Path.of(item.destinationPath()), item.isFile());
                        } catch (Exception ex) {
                            failedToDelete.add(item.destinationPath());
                            Logger.warn(ex);
                        }
                    }

                    processed++;
                    break;
                case DiskTaskType.COPY:
                    rewriteLinesWithProgress(
                            new String[]{String.format("copy %s", item.sourcePath()), getProgressInfoString(processed)},
                            percantage);

                    if (item.isFile())
                        try (FileInputStream sourceStream = new FileInputStream(item.sourcePath());
                             FileOutputStream destinationStream = new FileOutputStream(item.destinationPath())) {
                            int kb = 4;
                            byte[] buffer = new byte[1024 * kb];
                            int bytesRead = 0;

                            int counter = 0;
                            int counterMax = 1024 / kb;
                            while ((bytesRead = sourceStream.read(buffer, 0, buffer.length)) != -1) {
                                destinationStream.write(buffer, 0, bytesRead);
                                processed += bytesRead;
                                counter++;
                                if (counter == counterMax) {
                                    percantage = (int) ((processed * 100) / commonSize);
                                    rewriteLinesWithProgress(
                                            new String[]{
                                                    String.format("copy %s", item.sourcePath()),
                                                    getProgressInfoString(processed)},
                                            percantage);
                                    counter = 0;
                                }
                            }
                            sourceStream.close();
                            destinationStream.close();

                            BasicFileAttributes attr = Files.readAttributes(
                                    Path.of(item.sourcePath()), BasicFileAttributes.class);
                            Files.getFileAttributeView(Path.of(item.destinationPath()), BasicFileAttributeView.class)
                                    .setTimes(attr.lastModifiedTime(), null, attr.creationTime());

                        } catch (Exception e) {
                            failedToCopy.add(item.sourcePath());
                            Logger.warn(e);
                        }
                    break;
            }
        }

        rewriteLinesWithProgress(new String[]{"", "syncing complete"}, 100);
        writeLine();
    }

    private void deleteDirectory(Path path) throws IOException {
        Stream<Path> walk = Files.walk(path);
        walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        Logger.warn(e);
                    }
                });
    }

    private void forceDelete(Path path, boolean isFile) throws IOException {
        if (isFile) {
            Files.setAttribute(path, "dos:readonly", false);
            Files.delete(path);
        } else {
            for (var dir : getDirectories(path))
                if (dir != null)
                    forceDelete(dir, false);

            for (var file : getFiles(path))
                forceDelete(file, true);

            deleteDirectory(path);
        }
    }

    private boolean isPathsValid() {
        return Files.isDirectory(sourcePath) && Files.isDirectory(destinationPath);
    }

    private String getProgressInfoString(long processed) {
        return String.format("%s / %s MB", processed / 1024 / 1024, commonSize / 1024 / 1024);
    }

    private void enqueueDelete(String path, boolean isFile) {
        var task = new DiskTask(DiskTaskType.DELETE, "", path, isFile);
        diskTasks.offer(task);
    }

    private void enqueueCopy(String sourcePath, String destinationPath) {
        var task = new DiskTask(DiskTaskType.COPY, sourcePath, destinationPath, true);
        diskTasks.offer(task);
    }
}
