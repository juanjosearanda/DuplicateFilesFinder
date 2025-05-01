package dupfiles;

/**
 *
 * @author Juan Jose Aranda <juanjosearanda@gmail.com>
 * Apr 30, 2025
 * 
 * Example: java -jar /Users/jjaranda/NetBeansProjects/DupFiles/dist/DupFiles.jar -t CSV /Users/jjaranda/Juanito/ /Users/jjaranda/Downloads/ 
 */

// Command Line Parsing
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

// Standard Java imports
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
import java.util.Scanner;
import java.util.concurrent.Callable; // Import Callable for Picocli

// Main class annotated for Picocli
@Command(name = "DuplicateFileFinder",
         mixinStandardHelpOptions = true, // Adds --help, -h automatically
         version = "DuplicateFileFinder 1.1",
         description = "Finds files with identical content (size and SHA-256 hash) " +
                       "across two specified directories.")
public class DuplicateFileFinder implements Callable<Integer> { // Implement Callable

    // --- Picocli Command Line Options ---

    @Option(names = {"-t", "--type"}, description = "Only check files with this extension (e.g., PDF, txt, jpg). Case-insensitive.")
    private String fileTypeFilter = null; // Default: check all types

    @Option(names = {"-s", "--size"}, description = "Only check files larger than this size. " +
            "Use B/K/M/G suffixes (e.g., 1024, 500K, 10M, 1G). Case-insensitive. No suffix means bytes.")
    private String minSizeFilterStr = null; // Default: check all sizes >= 0

    @Parameters(index = "0", description = "Path to the first directory.", arity = "0..1") // Optional parameter
    private Path dir1Path = null;

    @Parameters(index = "1", description = "Path to the second directory.", arity = "0..1") // Optional parameter
    private Path dir2Path = null;

    // --- Class Members ---

    private final Map<Long, Map<String, List<Path>>> dir1FileIndex = new HashMap<>();
    private final List<DuplicatePair> duplicatePairs = new ArrayList<>();

    private static final int BUFFER_SIZE = 8192; // For reading files
    private static final String HASH_ALGORITHM = "SHA-256";

    private long minSizeBytes = 0; // Parsed minimum size in bytes
    private String normalizedFileTypeFilter = null; // Lowercase for comparison

    /**
     * Represents a pair of duplicate files found in the two directories.
     * (Inner class remains the same as before)
     */
    public static class DuplicatePair {
        private final Path fileInDir1;
        private final Path fileInDir2;

        public DuplicatePair(Path fileInDir1, Path fileInDir2) {
            this.fileInDir1 = fileInDir1;
            this.fileInDir2 = fileInDir2;
        }

        public Path getFileInDir1() { return fileInDir1; }
        public Path getFileInDir2() { return fileInDir2; }

        @Override
        public String toString() {
            return String.format("  - %s%n    (Identical to: %s)", fileInDir1, fileInDir2);
        }
        @Override
        public boolean equals(Object o) { /* ... same as before ... */
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DuplicatePair that = (DuplicatePair) o;
            return Objects.equals(fileInDir1, that.fileInDir1) && Objects.equals(fileInDir2, that.fileInDir2);
        }
        @Override
        public int hashCode() { /* ... same as before ... */
            return Objects.hash(fileInDir1, fileInDir2);
        }
    }

    // --- Picocli Execution Logic ---

    @Override
    public Integer call() throws Exception { // This method is executed by Picocli
        System.out.println("Starting duplicate file search...");

        // 1. Handle Directory Input (Prompt if missing)
        if (!promptAndValidateDirectories()) {
            return 1; // Exit code for invalid directories
        }

        // 2. Parse and Validate Filters
        if (!parseAndValidateFilters()) {
            return 1; // Exit code for invalid filter input
        }

        // --- Print Execution Info ---
        System.out.println("Directory 1: " + dir1Path.toAbsolutePath());
        System.out.println("Directory 2: " + dir2Path.toAbsolutePath());
        System.out.println("Comparison based on Size and " + HASH_ALGORITHM + " hash.");
        if (normalizedFileTypeFilter != null) {
            System.out.println("Filter: Only checking '." + normalizedFileTypeFilter + "' files.");
        }
        if (minSizeBytes > 0) {
            System.out.printf("Filter: Only checking files larger than %d bytes (%.2f MB).%n",
                              minSizeBytes, minSizeBytes / (1024.0 * 1024.0));
        }
         if (normalizedFileTypeFilter == null && minSizeBytes <= 0) {
            System.out.println("Filter: Checking all files.");
        }
        System.out.println("--------------------------------------------------");

        // --- Perform Scan ---
        long startTime = System.currentTimeMillis();
        try {
            System.out.println("Scanning Directory 1: " + dir1Path + " ...");
            buildFileIndex(dir1Path);
            System.out.println("Directory 1 scan complete. Indexed " + countIndexedFiles() + " potential candidate files matching filters.");

            System.out.println("\nScanning Directory 2: " + dir2Path + " and comparing...");
            findMatchesInSecondDirectory(dir2Path);
            System.out.println("Directory 2 scan and comparison complete.");

        } catch (IOException e) {
            System.err.println("\nAn I/O error occurred during the scan:");
            e.printStackTrace();
            return 2; // I/O Error exit code
        } catch (NoSuchAlgorithmException e) {
            System.err.println("\nFatal Error: Hash algorithm " + HASH_ALGORITHM + " not supported.");
            return 3; // Hashing algorithm error
        } catch (SecurityException se) {
            System.err.println("\nA security/permission error occurred:");
            se.printStackTrace();
            return 4; // Security error
        }

        // --- Report Results ---
        long endTime = System.currentTimeMillis();
        double durationSeconds = (endTime - startTime) / 1000.0;

        System.out.println("\n--------------------------------------------------");
        System.out.printf("Search finished in %.2f seconds.%n", durationSeconds);

        if (duplicatePairs.isEmpty()) {
            System.out.println("No duplicate files found matching the criteria between the two directories.");
        } else {
            System.out.println("Found " + duplicatePairs.size() + " pairs of identical files matching the criteria:");
            System.out.println("--------------------------------------------------");
            // Grouping by file in Dir1 for potentially clearer output
            Map<Path, List<Path>> groupedDuplicates = new HashMap<>();
            for(DuplicatePair pair : duplicatePairs) {
                groupedDuplicates.computeIfAbsent(pair.getFileInDir1(), k -> new ArrayList<>()).add(pair.getFileInDir2());
            }

            groupedDuplicates.forEach((file1, files2) -> {
                System.out.println("File in Dir 1: " + file1);
                System.out.println("  Identical file(s) in Dir 2:");
                files2.forEach(file2 -> System.out.println("    - " + file2));
                 System.out.println(); // Add a blank line for readability
            });
        }

        return 0; // Success exit code
    }

    // --- Directory Handling ---

    private boolean promptAndValidateDirectories() {
        Scanner scanner = null; // Initialize scanner, create only if needed

        try {
            // Prompt for Dir 1 if not provided
            while (dir1Path == null) {
                if (scanner == null) scanner = new Scanner(System.in);
                System.out.print("Enter path for Directory 1: ");
                String pathStr = scanner.nextLine().trim();
                if (pathStr.isEmpty()) continue;
                try {
                   dir1Path = Paths.get(pathStr);
                } catch (InvalidPathException e) {
                    System.err.println("ERROR: Invalid path syntax: " + pathStr);
                    dir1Path = null; // Reset to re-prompt
                }
            }

            // Prompt for Dir 2 if not provided
            while (dir2Path == null) {
                if (scanner == null) scanner = new Scanner(System.in);
                System.out.print("Enter path for Directory 2: ");
                String pathStr = scanner.nextLine().trim();
                 if (pathStr.isEmpty()) continue;
                 try {
                   dir2Path = Paths.get(pathStr);
                 } catch (InvalidPathException e) {
                    System.err.println("ERROR: Invalid path syntax: " + pathStr);
                    dir2Path = null; // Reset to re-prompt
                 }
            }
        } finally {
            if (scanner != null) {
                // scanner.close(); // Closing System.in scanner is often problematic, avoid unless necessary.
            }
        }


        // Validate Dir 1
        if (!Files.isDirectory(dir1Path)) {
            System.err.println("ERROR: Not a valid directory: " + dir1Path.toAbsolutePath());
            return false;
        }
        // Validate Dir 2
        if (!Files.isDirectory(dir2Path)) {
            System.err.println("ERROR: Not a valid directory: " + dir2Path.toAbsolutePath());
            return false;
        }
        // Check if directories are the same
        try {
            if (Files.isSameFile(dir1Path, dir2Path)) {
                 System.err.println("ERROR: Directories must be different.");
                 return false;
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not definitively compare directory paths: " + e.getMessage());
            // Continue, but paths might resolve to the same location symbolically
            if (dir1Path.equals(dir2Path)) { // Fallback check
                 System.err.println("ERROR: Directory paths appear identical.");
                 return false;
            }
        }


        // Check for overlap (optional but good practice)
        if (dir1Path.startsWith(dir2Path) || dir2Path.startsWith(dir1Path)) {
            System.out.println("Warning: One directory appears to be inside the other.");
            System.out.println("Ensure this is intended, as results might be compared inefficiently.");
        }

        return true; // Directories are valid
    }

    // --- Filter Parsing ---

    private boolean parseAndValidateFilters() {
        // Parse Min Size
        if (minSizeFilterStr != null && !minSizeFilterStr.trim().isEmpty()) {
            try {
                minSizeBytes = parseSizeString(minSizeFilterStr);
                if (minSizeBytes < 0) {
                    System.err.println("ERROR: Minimum size cannot be negative.");
                    return false;
                }
            } catch (IllegalArgumentException e) {
                System.err.println("ERROR: Invalid size format: " + minSizeFilterStr + ". " + e.getMessage());
                System.err.println("Use numbers optionally followed by K, M, or G (e.g., 500K, 10M, 1G).");
                return false;
            }
        } else {
            minSizeBytes = 0; // No filter or empty string means check all sizes >= 0
        }

        // Normalize File Type Filter
        if (fileTypeFilter != null && !fileTypeFilter.trim().isEmpty()) {
            normalizedFileTypeFilter = fileTypeFilter.trim().toLowerCase();
            // Remove leading dot if present
            if (normalizedFileTypeFilter.startsWith(".")) {
                normalizedFileTypeFilter = normalizedFileTypeFilter.substring(1);
            }
             if (normalizedFileTypeFilter.isEmpty()) {
                System.err.println("ERROR: File type filter cannot be empty if specified.");
                normalizedFileTypeFilter = null; // Treat as no filter
                return false;
             }
             if (normalizedFileTypeFilter.contains(".") || normalizedFileTypeFilter.contains(java.io.File.separator)) {
                System.err.println("ERROR: Invalid file type filter: '"+ fileTypeFilter +"'. Should be an extension like 'pdf' or 'txt'.");
                return false;
             }

        } else {
            normalizedFileTypeFilter = null; // No filter applied
        }

        return true;
    }

    // --- Core Logic (Modified for Filters) ---

    private int countIndexedFiles() { /* ... same as before ... */
        int count = 0;
        for (Map<String, List<Path>> hashes : dir1FileIndex.values()) {
            for (List<Path> paths : hashes.values()) {
                count += paths.size();
            }
        }
        return count;
    }

    private void buildFileIndex(Path directory) throws IOException, NoSuchAlgorithmException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // --- APPLY FILTERS ---
                if (!shouldProcessFile(file, attrs)) {
                    return FileVisitResult.CONTINUE; // Skip this file
                }

                // --- Process File (if filters pass) ---
                if (attrs.isRegularFile()) { // Size > 0 check is now in shouldProcessFile
                    try {
                        long fileSize = attrs.size();
                        String fileHash = calculateHash(file);
                        if (fileHash != null) {
                            Map<String, List<Path>> hashesForSize = dir1FileIndex.computeIfAbsent(fileSize, k -> new HashMap<>());
                            List<Path> pathsForHash = hashesForSize.computeIfAbsent(fileHash, k -> new ArrayList<>());
                            pathsForHash.add(file);
                        }
                    } catch (NoSuchAlgorithmException e) {
                        // Re-throw as unchecked to signal fatal error from walker
                        throw new RuntimeException("Hash algorithm error", e);
                    } catch (IOException | SecurityException e) {
                        System.err.println("WARNING: Could not read/hash file in Dir 1: " + file + " (" + e.getMessage() + ")");
                        // Continue scanning other files
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                System.err.println("WARNING: Failed to access in Dir 1: " + file + " (" + exc.getMessage() + ")");
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void findMatchesInSecondDirectory(Path directory) throws IOException, NoSuchAlgorithmException {
         Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file2, BasicFileAttributes attrs) throws IOException {
                 // --- APPLY FILTERS ---
                 if (!shouldProcessFile(file2, attrs)) {
                    return FileVisitResult.CONTINUE; // Skip this file
                 }

                 // --- Process File (if filters pass) ---
                 if (attrs.isRegularFile()) {
                    long fileSize = attrs.size();
                    if (dir1FileIndex.containsKey(fileSize)) {
                        try {
                            String file2Hash = calculateHash(file2);
                            if (file2Hash != null) {
                                Map<String, List<Path>> hashesForSize = dir1FileIndex.get(fileSize);
                                if (hashesForSize.containsKey(file2Hash)) {
                                    List<Path> matchingFilesDir1 = hashesForSize.get(file2Hash);
                                    for (Path file1 : matchingFilesDir1) {
                                        duplicatePairs.add(new DuplicatePair(file1, file2));
                                    }
                                }
                            }
                        } catch (NoSuchAlgorithmException e) {
                            throw new RuntimeException("Hash algorithm error", e);
                        } catch (IOException | SecurityException e) {
                            System.err.println("WARNING: Could not read/hash file in Dir 2: " + file2 + " (" + e.getMessage() + ")");
                        }
                    }
                 }
                return FileVisitResult.CONTINUE;
            }

             @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                System.err.println("WARNING: Failed to access in Dir 2: " + file + " (" + exc.getMessage() + ")");
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // --- Helper Methods ---

    /**
     * Checks if a file should be processed based on active filters (size, type).
     */
    private boolean shouldProcessFile(Path file, BasicFileAttributes attrs) {
         // Ignore non-regular files and directories implicitly by checking isRegularFile later,
         // but explicit size check is needed here.
        if (!attrs.isRegularFile()) return false; // Only process regular files

        // Size Filter
        if (attrs.size() <= 0) return false; // Ignore empty files anyway
        if (minSizeBytes > 0 && attrs.size() <= minSizeBytes) {
            return false; // File is not larger than the minimum required size
        }

        // Type Filter
        if (normalizedFileTypeFilter != null) {
            String extension = getFileExtension(file);
            if (!normalizedFileTypeFilter.equals(extension)) { // Case is handled by normalization
                return false; // Extension doesn't match
            }
        }

        // If all checks pass
        return true;
    }

    /**
     * Calculates the SHA-256 hash of a file. (Same as before)
     */
    private String calculateHash(Path filePath) throws NoSuchAlgorithmException, IOException {
        // ... (Hashing logic remains exactly the same as the previous version) ...
        MessageDigest sha256 = MessageDigest.getInstance(HASH_ALGORITHM);
        try (InputStream is = Files.newInputStream(filePath);
             DigestInputStream dis = new DigestInputStream(is, sha256))
        {
            byte[] buffer = new byte[BUFFER_SIZE];
            //noinspection StatementWithEmptyBody
            while (dis.read(buffer) != -1) { /* Reading updates digest */ }
        }
        byte[] digestBytes = sha256.digest();
        return bytesToHex(digestBytes);

    }

    /**
     * Converts a byte array to its hexadecimal string representation. (Same as before)
     */
    private static String bytesToHex(byte[] hash) {
        // ... (bytesToHex logic remains exactly the same as the previous version) ...
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Gets the lowercase file extension from a Path.
     * Returns an empty string if no extension is found.
     */
    private String getFileExtension(Path path) {
        String fileName = path.getFileName().toString();
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
            // Ensure dot is not the first or last character
            return fileName.substring(lastDotIndex + 1).toLowerCase();
        }
        return ""; // No extension found
    }

    /**
     * Parses a size string (e.g., "10M", "2G", "512K") into bytes.
     * Case-insensitive. No suffix means bytes.
     * @throws IllegalArgumentException if the format is invalid.
     */
    private long parseSizeString(String sizeStr) throws IllegalArgumentException {
        if (sizeStr == null || sizeStr.trim().isEmpty()) {
            throw new IllegalArgumentException("Size string cannot be empty.");
        }
        sizeStr = sizeStr.trim().toUpperCase();

        long multiplier = 1;
        char lastChar = sizeStr.charAt(sizeStr.length() - 1);

        if (!Character.isDigit(lastChar)) {
            switch (lastChar) {
                case 'G':
                    multiplier = 1024L * 1024 * 1024;
                    break;
                case 'M':
                    multiplier = 1024L * 1024;
                    break;
                case 'K':
                    multiplier = 1024L;
                    break;
                case 'B': // Explicitly allow 'B' for bytes
                     multiplier = 1;
                     break;
                default:
                    throw new IllegalArgumentException("Invalid size suffix: '" + lastChar + "'. Use K, M, or G.");
            }
            sizeStr = sizeStr.substring(0, sizeStr.length() - 1); // Remove suffix
        }

        try {
            // Allow decimal values for K, M, G (e.g., 1.5M)
            if (sizeStr.contains(".")) {
                 if (multiplier == 1) {
                     throw new IllegalArgumentException("Decimal values only allowed with K, M, or G suffix.");
                 }
                 double value = Double.parseDouble(sizeStr);
                 return (long) (value * multiplier);
            } else {
                long value = Long.parseLong(sizeStr);
                 if (value < 0) throw new IllegalArgumentException("Size value cannot be negative.");
                return value * multiplier;
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number format in size string: '" + sizeStr + "'");
        }
    }


    // --- Main Method (Now delegates to Picocli) ---
    public static void main(String[] args) {
        // Let Picocli parse arguments, handle help, and call the 'call' method
        int exitCode = new CommandLine(new DuplicateFileFinder()).execute(args);
        System.exit(exitCode);
    }
}
