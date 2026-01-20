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
    private final Path _sourceDirectory;
    private final Path _destinationDirectory;
    private Queue<DiskTask> _diskTasks = new LinkedList<>();
    private List<String> _failedToDelete = new ArrayList<>();
    private List<String> _failedToCopy = new ArrayList<>();
    private List<String> _failedToScan = new ArrayList<>();
    private long _commonSize = 0;

    public FileSynchronizer(String sourcePath, String destinationPath)
    {
        _sourceDirectory = Path.of(sourcePath);
        _destinationDirectory = Path.of(destinationPath);
    }

    public void synchronize() throws Exception {
        if (!isPathsValid())
            throw new Exception("invalid path");

        writeLine("scanning...");
        scanDirectories(_sourceDirectory, _destinationDirectory);

        long dirToDelete = _diskTasks.stream()
                .filter(t -> !t.IsFile && t.TaskType == DiskTaskType.Delete)
                .count();
        long filesToDelete = _diskTasks.stream()
                .filter(t -> t.IsFile && t.TaskType == DiskTaskType.Delete)
                .count();
        long filesToCopy = _diskTasks.stream()
                .filter(t -> t.IsFile && t.TaskType == DiskTaskType.Copy)
                .count();

        rewriteLines(new String[]
        {
            "scanning complete",
            String.format("%s directories %s files to delete", dirToDelete, filesToDelete)
        });
        writeLine(String.format("\n%s filesToCopy", filesToCopy));

        writeLine("\nsyncing...\n\n");
        syncDirectories();

        if (_failedToScan.toArray().length > 0){
            writeLine("\nfailed to scan:");
            for (var item : _failedToScan)
                writeLine(item);
        }

        if (_failedToDelete.toArray().length > 0)
        {
            writeLine("\nfailed to delete:");
            for (var item : _failedToDelete)
                writeLine(item);
        }

        if (_failedToCopy.toArray().length > 0)
        {
            writeLine("\nfailed to copy:");
            for (var item : _failedToCopy)
                writeLine(item);
        }
    }

    private void scanDirectories(Path source, Path destination) throws IOException {
        rewriteLines(new String[] { source.toString().replace(" ", "") });

        try{
            // delete directories
            for (var directory : getDirectories(destination))
            {
                if (!Files.exists(Path.of(source.toString(), directory.getFileName().toString())))
                {
                    enqueueDelete(directory.toString(), false);
                    _commonSize++;
                }
            }

            // create directories
            for (var directory : getDirectories(source))
            {
                Path destDirectory = Path.of(destination.toString(), directory.getFileName().toString());
                if (!Files.exists(destDirectory)) {
                    try {
                        Files.createDirectory(destDirectory);
                    } catch (IOException e) {}
                }

                scanDirectories(directory, destDirectory);
            }

            // delete files
            for (var file : getFiles(destination))
            {
                Path sourceFile = Path.of(source.toString(), file.getFileName().toString());
                if (!Files.exists(sourceFile))
                {
                    enqueueDelete(file.toString(), true);
                    _commonSize++;
                }
                else if (Files.getLastModifiedTime(sourceFile).toMillis() != Files.getLastModifiedTime(file).toMillis()){
                    enqueueDelete(file.toString(), true);
                    _commonSize++;
                    enqueueCopy(sourceFile.toString(), file.toString());
                    _commonSize += Files.size(sourceFile);
                }
            }

            // copy files
            for (var file : getFiles(source))
            {
                Path destFile = Path.of(destination.toString(), file.getFileName().toString());
                if (!Files.exists(destFile))
                {
                    enqueueCopy(file.toString(), destFile.toString());
                    _commonSize += Files.size(file);
                }
            }
        }
        catch (Exception e){
            _failedToScan.add(String.format("source: %s; destination: %s", source, destination));
        }
    }

    private List<Path> getDirectories(Path path){
        try (Stream<Path> stream = Files.list(path)) {
            List<Path> subFolders = stream
                    .filter(Files::isDirectory)
                    .toList();
            if (subFolders.size() > 0)
                return subFolders;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }

    private List<Path> getFiles(Path path){
        try (Stream<Path> stream = Files.list(path)) {
            List<Path> files = stream
                    .filter(f -> !Files.isDirectory(f))
                    .toList();
            if (files.size() > 0)
                return files;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }

    private void syncDirectories()
    {
        long processed = 0;

        int dtCount = _diskTasks.toArray().length;
        for (int i = 0; i < dtCount; i++)
        {
            int percantage = (int)((processed * 100) / dtCount);
            DiskTask item = _diskTasks.poll();

            switch (item.TaskType)
            {
                case DiskTaskType.Delete:
                    rewriteLinesWithProgress(
                            new String[] {String.format("delete %s", item.DestinationPath), getProgressInfoString(processed) },
                            percantage);

                    try
                    {
                        if (item.IsFile)
                            Files.deleteIfExists(Path.of(item.DestinationPath));
                        else
                            deleteDirectory(Path.of(item.DestinationPath));
                    }
                    catch(Exception e)
                    {
                        try
                        {
                            forceDelete(Path.of(item.DestinationPath), item.IsFile);
                        }
                        catch (Exception ex)
                        {
                            _failedToDelete.add(item.DestinationPath);
                        }
                    }

                processed++;
                break;
                case DiskTaskType.Copy:
                    Logger.info(String.format("copy %s", item.SourcePath));
                    rewriteLinesWithProgress(
                            new String[] { String.format("copy %s", item.SourcePath), getProgressInfoString(processed) },
                            percantage);

                    if (item.IsFile)
                        try (FileInputStream sourceStream = new FileInputStream(item.SourcePath);
                                FileOutputStream destinationStream = new FileOutputStream(item.DestinationPath)){
                            int kb = 4;
                            byte[] buffer = new byte[1024 * kb];
                            int bytesRead = 0;

                            int counter = 0;
                            int counterMax = 1024 / kb;
                            while((bytesRead = sourceStream.read(buffer, 0, buffer.length)) != -1)
                            {
                                destinationStream.write(buffer, 0, bytesRead);
                                processed += bytesRead;
                                counter++;
                                if (counter == counterMax)
                                {
                                    percantage = (int)((processed * 100) / _commonSize);
                                    rewriteLinesWithProgress(
                                            new String[] { String.format("copy %s", item.SourcePath), getProgressInfoString(processed) },
                                            percantage);
                                    counter = 0;
                                }
                            }
                            sourceStream.close();
                            destinationStream.close();

                            BasicFileAttributes attr = Files.readAttributes(Path.of(item.SourcePath), BasicFileAttributes.class);
                            Files.getFileAttributeView(Path.of(item.DestinationPath), BasicFileAttributeView.class)
                                    .setTimes(attr.lastModifiedTime(), null, attr.creationTime());

                        }
                        catch(Exception e)
                        {
                            _failedToCopy.add(item.SourcePath);
                        }
                    break;
            }
        }

        rewriteLinesWithProgress(new String[] { "", "syncing complete" }, 100);
        writeLine("");
    }

    private void deleteDirectory(Path path) throws IOException {
        Stream<Path> walk = Files.walk(path);
        walk.sorted(Comparator.reverseOrder())
                .forEach(p ->
                {
                    try{
                        Files.delete(p);
                    } catch (IOException e) {}
                });
    }

    private void forceDelete(Path path, boolean isFile) throws IOException {
        if (isFile)
        {
            Files.setAttribute(path, "dos:readonly", false);
            Files.delete(path);
        }
        else
        {
            for (var dir : getDirectories(path))
                if (dir != null)
                    forceDelete(dir, false);

            for (var file : getFiles(path))
                forceDelete(file, true);

            deleteDirectory(path);
        }
    }

    private boolean isPathsValid(){
        return Files.isDirectory(_sourceDirectory) && Files.isDirectory(_destinationDirectory);
    }

    private String getProgressInfoString(long processed){
        return String.format("%s / %s MB", processed / 1024 / 1024, _commonSize / 1024 / 1024);
    }

    private void enqueueDelete(String path, boolean isFile)
    {
        var task = new DiskTask();
        task.TaskType = DiskTaskType.Delete;
        task.DestinationPath = path;
        task.IsFile = isFile;
        _diskTasks.offer(task);
    }

    private void enqueueCopy(String sourcePath, String destinationPath)
    {
        var task = new DiskTask();
        task.TaskType = DiskTaskType.Copy;
        task.SourcePath = sourcePath;
        task.DestinationPath = destinationPath;
        task.IsFile = true;
        _diskTasks.offer(task);
    }
}
