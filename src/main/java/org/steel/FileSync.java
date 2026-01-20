package org.steel;

import org.tinylog.Logger;

public class FileSync {
    public static void main(String[] args) {
        try {
            if (args.length != 2)
                throw new IllegalArgumentException("wrong number of arguments");

            String sourcePath = args[0];
            String destinationPath = args[1];

            FileSynchronizer fs = new FileSynchronizer(sourcePath, destinationPath);
            fs.synchronize();
        } catch (IllegalArgumentException e){
            System.out.println(e.getMessage());
            Logger.error(e.getMessage());
        } catch (Exception e){
            System.out.println(e.getMessage());
            Logger.error(e, e.getMessage());
        }
    }
}