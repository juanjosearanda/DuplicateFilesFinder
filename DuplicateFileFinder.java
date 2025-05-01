/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Main.java to edit this template
 */
package duplicatefilefinder;

/**
 *
 * @author Juan Jose Aranda Aboy
 * Apr 27,2025
 * 
 * 
 Complete Java application to find duplicate files based on size and SHA-256 hash across two directories.
Features:
Command-line execution: Takes two directory paths as arguments.
Recursive Scan: Scans both specified directories and their subdirectories.
Size Check First: Optimizes by only calculating hashes for files with matching sizes.
SHA-256 Hashing: Uses SHA-256 for robust content comparison.
Handles Different Names/Extensions: Focuses solely on content identity.
Clear Output: Lists pairs of identical files found in the two different base directories.
Error Handling: Basic checks for directory validity and handles potential I/O or hashing errors.
Modern Java: Uses java.nio.file API and try-with-resources.

 */
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DuplicateFileFinder {

    // Data structure to store file info: Map<Size, Map<Hash, List<Path>>>
    private final Map<Long, Map<String, List<Path>>> dir1FileIndex = new HashMap<>();
    private final List<DuplicatePair> duplicatePairs = new ArrayList<>();

    private static final int BUFFER_SIZE = 8192; // For reading files
    private static final String HASH_ALGORITHM = "SHA-256";

    /**
     * Represents a pair of duplicate files found in the two directories.
     */
    public static class DuplicatePair {
        private final Path fileInDir1;
        private final Path fileInDir2;

        public DuplicatePair(Path fileInDir1, Path fileInDir2) {
            this.fileInDir1 = fileInDir1;
            this.fileInDir2 = fileInDir2;
        }

        public Path getFileInDir1() {
            return fileInDir1;
        }

        public Path getFileInDir2() {
            return fileInDir2;
        }

        @Override
        public String toString() {
            return String.format("  - %s%n    (Identical to: %s)", fileInDir1, fileInDir2);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DuplicatePair that = (DuplicatePair) o;
            // Order doesn't matter for equality check in a set, but matters for our list
            return Objects.equals(fileInDir1, that.fileInDir1) && Objects.equals(fileInDir2, that.fileInDir2);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fileInDir1, fileInDir2);
        }
    }

    /**
     * Finds duplicate files between two directories.
     *
     * @param dir1Path Path to the first directory.
     * @param dir2Path Path to the second directory.
     * @return A list of DuplicatePair objects representing identical files.
     * @throws IOException If an I/O error occurs during file traversal or reading.
     * @throws NoSuchAlgorithmException If the SHA-256 algorithm is not available.
     */
    public List<DuplicatePair> findDuplicates(Path dir1Path, Path dir2Path)
            throws IOException, NoSuchAlgorithmException {

        System.out.println("Scanning Directory 1: " + dir1Path + " ...");
        buildFileIndex(dir1Path);
        System.out.println("Directory 1 scan complete. Indexed " + countIndexedFiles() + " files.");

        System.out.println("\nScanning Directory 2: " + dir2Path + " and comparing...");
        findMatchesInSecondDirectory(dir2Path);
        System.out.println("Directory 2 scan and comparison complete.");

        return duplicatePairs;
    }

    private int countIndexedFiles() {
        int count = 0;
        for (Map<String, List<Path>> hashes : dir1FileIndex.values()) {
            for (List<Path> paths : hashes.values()) {
                count += paths.size();
            }
        }
        return count;
    }

    /**
     * Scans the first directory and builds an index based on size and hash.
     */
    private void buildFileIndex(Path directory) throws IOException, NoSuchAlgorithmException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && attrs.size() > 0) { // Ignore empty files for simplicity, or handle as needed
                    try {
                        long fileSize = attrs.size();
                        // Only calculate hash if needed (i.e., first time seeing this size or potential collision)
                        // We calculate hash directly here for simplicity in this structure.
                        String fileHash = calculateHash(file);
                        if (fileHash != null) {
                            // Get or create the map for this size
                            Map<String, List<Path>> hashesForSize = dir1FileIndex.computeIfAbsent(fileSize, k -> new HashMap<>());
                            // Get or create the list for this hash
                            List<Path> pathsForHash = hashesForSize.computeIfAbsent(fileHash, k -> new ArrayList<>());
                            // Add the current file path
                            pathsForHash.add(file);
                        }
                    } catch (NoSuchAlgorithmException e) {
                        // Should not happen with SHA-256 unless platform is broken
                        System.err.println("ERROR: Hash algorithm " + HASH_ALGORITHM + " not found.");
                        throw new RuntimeException(e); // Re-throw to stop processing
                    } catch (IOException e) {
                        System.err.println("WARNING: Could not read or hash file: " + file + " (" + e.getMessage() + ")");
                        // Decide whether to continue or stop. Continuing here.
                    } catch (SecurityException se) {
                         System.err.println("WARNING: Permission denied for file: " + file + " (" + se.getMessage() + ")");
                         // Continue scanning other files
                    }
                } else if (attrs.isDirectory()) {
                    // Optionally print directory being scanned
                     // System.out.println("Scanning subdir: " + file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                System.err.println("WARNING: Failed to access: " + file + " (" + exc.getMessage() + ")");
                return FileVisitResult.CONTINUE; // Continue with other files/directories
            }
        });
    }

    /**
     * Scans the second directory and compares files against the index from the first.
     */
    private void findMatchesInSecondDirectory(Path directory) throws IOException, NoSuchAlgorithmException {
         Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file2, BasicFileAttributes attrs) throws IOException {
                 if (attrs.isRegularFile() && attrs.size() > 0) {
                    long fileSize = attrs.size();

                    // Optimization: Check if any file with this size exists in dir1
                    if (dir1FileIndex.containsKey(fileSize)) {
                        try {
                            String file2Hash = calculateHash(file2);
                            if (file2Hash != null) {
                                Map<String, List<Path>> hashesForSize = dir1FileIndex.get(fileSize);
                                // Check if this specific hash exists for this size in dir1
                                if (hashesForSize.containsKey(file2Hash)) {
                                    List<Path> matchingFilesDir1 = hashesForSize.get(file2Hash);
                                    for (Path file1 : matchingFilesDir1) {
                                        // Found a duplicate!
                                        duplicatePairs.add(new DuplicatePair(file1, file2));
                                    }
                                }
                            }
                        } catch (NoSuchAlgorithmException e) {
                             System.err.println("ERROR: Hash algorithm " + HASH_ALGORITHM + " not found.");
                             throw new RuntimeException(e);
                        } catch (IOException e) {
                            System.err.println("WARNING: Could not read or hash file: " + file2 + " (" + e.getMessage() + ")");
                        } catch (SecurityException se) {
                             System.err.println("WARNING: Permission denied for file: " + file2 + " (" + se.getMessage() + ")");
                        }
                    }
                 }
                return FileVisitResult.CONTINUE;
            }

             @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                System.err.println("WARNING: Failed to access: " + file + " (" + exc.getMessage() + ")");
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Calculates the SHA-256 hash of a file.
     *
     * @param filePath Path to the file.
     * @return Hexadecimal string representation of the hash, or null if an error occurs.
     * @throws NoSuchAlgorithmException If SHA-256 is not available.
     * @throws IOException If an I/O error occurs reading the file.
     */
    private String calculateHash(Path filePath) throws NoSuchAlgorithmException, IOException {
        MessageDigest sha256 = MessageDigest.getInstance(HASH_ALGORITHM);

        try (InputStream is = Files.newInputStream(filePath);
             DigestInputStream dis = new DigestInputStream(is, sha256))
        {
            byte[] buffer = new byte[BUFFER_SIZE];
            // Read the file through the DigestInputStream. This updates the digest automatically.
            //noinspection StatementWithEmptyBody
            while (dis.read(buffer) != -1) {
                // Reading happens here, updating the digest
            }
        } // try-with-resources ensures streams are closed

        // Get the hash bytes
        byte[] digestBytes = sha256.digest();

        // Convert bytes to hex string
        return bytesToHex(digestBytes);
    }

    /**
     * Converts a byte array to its hexadecimal string representation.
     */
    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    // --- Main Method ---
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java com.example.duplicatefinder.DuplicateFileFinder <directory1> <directory2>");
            System.exit(1);
        }

        Path dir1 = Paths.get(args[0]);
        Path dir2 = Paths.get(args[1]);

        // Validate directories
        if (!Files.isDirectory(dir1)) {
            System.err.println("Error: Not a valid directory: " + args[0]);
            System.exit(1);
        }
        if (!Files.isDirectory(dir2)) {
            System.err.println("Error: Not a valid directory: " + args[1]);
            System.exit(1);
        }
         if (dir1.equals(dir2)) {
            System.err.println("Error: Directories must be different.");
            System.exit(1);
        }

        // --- Check for potential overlap (e.g., one dir inside another) ---
        try {
             if (dir1.startsWith(dir2) || dir2.startsWith(dir1)) {
                System.out.println("Warning: One directory appears to be inside the other.");
                System.out.println("Results might include files compared against themselves if paths overlap significantly.");
                // Consider adding more sophisticated overlap checks or logic if needed.
            }
        } catch (InvalidPathException e) {
             // Less likely here as we validated isDirectory, but good practice
             System.err.println("Error comparing directory paths: " + e.getMessage());
             System.exit(1);
        }


        System.out.println("Starting duplicate file search...");
        System.out.println("Directory 1: " + dir1.toAbsolutePath());
        System.out.println("Directory 2: " + dir2.toAbsolutePath());
        System.out.println("Comparison based on Size and " + HASH_ALGORITHM + " hash.");
        System.out.println("--------------------------------------------------");

        long startTime = System.currentTimeMillis();
        DuplicateFileFinder finder = new DuplicateFileFinder();

        try {
            List<DuplicatePair> duplicates = finder.findDuplicates(dir1, dir2);

            long endTime = System.currentTimeMillis();
            double durationSeconds = (endTime - startTime) / 1000.0;

            System.out.println("\n--------------------------------------------------");
            System.out.printf("Search finished in %.2f seconds.%n", durationSeconds);

            if (duplicates.isEmpty()) {
                System.out.println("No duplicate files found between the two directories.");
            } else {
                System.out.println("Found " + duplicates.size() + " pairs of identical files:");
                System.out.println("--------------------------------------------------");
                // Grouping by file in Dir1 for potentially clearer output
                Map<Path, List<Path>> groupedDuplicates = new HashMap<>();
                for(DuplicatePair pair : duplicates) {
                    groupedDuplicates.computeIfAbsent(pair.getFileInDir1(), k -> new ArrayList<>()).add(pair.getFileInDir2());
                }

                groupedDuplicates.forEach((file1, files2) -> {
                    System.out.println("File in Dir 1: " + file1);
                    System.out.println("  Identical file(s) in Dir 2:");
                    files2.forEach(file2 -> System.out.println("    - " + file2));
                     System.out.println(); // Add a blank line for readability
                });
            }

        } catch (IOException e) {
            System.err.println("\nAn I/O error occurred during the scan:");
            e.printStackTrace();
            System.exit(2);
        } catch (NoSuchAlgorithmException e) {
            // Already handled inside, but catch here for safety
            System.err.println("\nFatal Error: Hash algorithm " + HASH_ALGORITHM + " not supported by this Java environment.");
            System.exit(3);
        } catch (Exception e) {
            System.err.println("\nAn unexpected error occurred:");
            e.printStackTrace();
             System.exit(4);
        }
    }
}