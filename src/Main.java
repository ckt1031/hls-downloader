package src;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final String DEFAULT_OUTPUT_DIR = "dist";
    private static final String DEFAULT_TEMP_DIR = "temp";

    // No clean up flag
    private static Boolean CLEAN = false;

    private static String parseArgument(String[] args, String flag) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(flag) && i + 1 < args.length) {
                return args[++i];
            }
        }
        return null;
    }

    private static Boolean hasArgument(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) throws IOException {
        List<String> urls = new ArrayList<>();
        String urlsFile = parseArgument(args, "-i");
        String outputFile = parseArgument(args, "-o");
        CLEAN = hasArgument(args, "--clean");

        for (String arg : args) {
            if (!arg.startsWith("-") && !arg.equals(urlsFile) && !arg.equals(outputFile)) {
                urls.add(String.format("%s:%s", outputFile, arg));
            }
        }

        if (urlsFile != null) {
            try (BufferedReader reader = new BufferedReader(new FileReader(urlsFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Ignore if # or empty
                    if (line.startsWith("#") || line.isEmpty()) {
                        continue;
                    }

                    if (line.contains(":")) {
                        String[] parts = line.split(":", 2);
                        urls.add(String.format("%s:%s", parts[0], parts[1].trim()));
                    } else {
                        urls.add(line.trim());
                    }
                }
            }
        }

        if (urls.isEmpty()) {
            System.out.println("Usage: java HLSDownloader [-t urls_file] <m3u8_url>...");
            return;
        }

        // Ensure dist folder exists:
        Files.createDirectories(Paths.get(DEFAULT_OUTPUT_DIR));
        // Ensure temp folder exists:
        Files.createDirectories(Paths.get(DEFAULT_TEMP_DIR));

        for (String url : urls) {
            // System.out.println(url);
            String[] parts = url.split(":", 2);
            String filename = parts[0] == null ? "" : parts[0];

            // Check if file exists in system
            if (Files.exists(Paths.get(DEFAULT_OUTPUT_DIR, filename))) {
                System.out.println("File already exists: " + filename);
                continue;
            }

            // System.out.println("Downloading " + parts[1] + " to " + filename);
            download(filename, parts[1]);
        }
    }

    private static void download(String filename, String urlString) {
        try {
            List<String> tsFiles = fetchM3U8(urlString);

            URL url = new URI(urlString).toURL();
            String path = url.getPath();
            String hash = getMD5(path);
            String fileNameParsed = filename.isEmpty() || !filename.endsWith(".mp3") ? hash + ".mp3" : filename;

            downloadTsFiles(hash, urlString, tsFiles);
            mergeFilesToMP3(hash, fileNameParsed, tsFiles);

            // Clean up temp files
            if (CLEAN) {
                Files.walk(Paths.get(DEFAULT_TEMP_DIR, hash)).map(java.nio.file.Path::toFile).forEach(File::delete);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void downloadTsFiles(String idenifier, String playlistURL, List<String> tsFiles) throws Exception {
        Files.createDirectories(Paths.get(DEFAULT_TEMP_DIR, idenifier));

        String lastPath = getLastPath(playlistURL);

        System.out.println("Downloading " + tsFiles.size() + " files...");

        Integer threads = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threads); // Adjust the number of threads as needed

        for (String tsFile : tsFiles) {
            executor.submit(() -> {
                try {
                    // Get index
                    int index = tsFiles.indexOf(tsFile);
                    String fileName = String.format("%04d", index) + ".ts";

                    // Ignore if the file already exists
                    if (Files.exists(Paths.get(DEFAULT_TEMP_DIR, idenifier, fileName))) {
                        return;
                    }

                    String urlString = playlistURL.replace(lastPath, tsFile);
                    URL tsURL = new URI(urlString).toURL();
                    HttpURLConnection conn = (HttpURLConnection) tsURL.openConnection();
                    conn.setRequestMethod("GET");

                    InputStream in = conn.getInputStream();
                    Files.copy(in, Paths.get(DEFAULT_TEMP_DIR, idenifier, fileName));
                    in.close();

                    System.out.println("Downloaded: " + fileName);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();

        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static void mergeFilesToMP3(String idenifier, String fileName, List<String> tsFiles) throws Exception {
        // Check if exists
        if (Files.exists(Paths.get(DEFAULT_OUTPUT_DIR, fileName))) {
            System.out.println("File already exists: " + fileName);
            return;
        }

        List<String> files = new ArrayList<>();
        for (String tsFile : tsFiles) {
            int index = tsFiles.indexOf(tsFile);
            String tsFileName = String.format("%04d", index) + ".ts";
            files.add(Paths.get(DEFAULT_TEMP_DIR, idenifier, tsFileName).toString());
        }

        // FFmpeg command to merge the .ts files into a .mp3 with compression
        ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-i", "concat:" + String.join("|", files),
                "-vn", "-acodec", "libmp3lame", "-ab", "256k", "-ar", "44100", "-ac", "2", "-f", "mp3", "-y",
                Paths.get(DEFAULT_OUTPUT_DIR, fileName).toString());

        pb.inheritIO();
        Process p = pb.start();
        p.waitFor();
    }

    private static List<String> fetchM3U8(String urlString) throws Exception {
        URL url = new URI(urlString).toURL();

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        List<String> chunkList = new ArrayList<>();
        while ((inputLine = in.readLine()) != null) {
            if (!inputLine.startsWith("#")) {
                chunkList.add(inputLine);
            }
        }
        in.close();

        String lastpath = getLastPath(urlString);

        List<String> tsFiles = new ArrayList<>();

        for (String chunk : chunkList) {
            String newURL = urlString.replace(lastpath, chunk);
            List<String> list = fetchTsFilesList(newURL);
            tsFiles.addAll(list);
        }

        return tsFiles;
    }

    private static List<String> fetchTsFilesList(String chunkListURL) throws Exception {
        // Fetch the .ts files list
        URL url = new URI(chunkListURL).toURL();

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        List<String> tsFiles = new ArrayList<>();
        while ((inputLine = in.readLine()) != null) {
            if (inputLine.endsWith(".ts")) {
                tsFiles.add(inputLine);
            }
        }
        in.close();

        return tsFiles;
    }

    private static String getLastPath(String urlString) throws Exception {
        URL url = new URI(urlString).toURL();
        String path = url.getPath(); // Get the path part of the URL
        String[] segments = path.split("/"); // Split the path by "/"
        String target = segments[segments.length - 1]; // Get the last segment
        return target;
    }

    private static String getMD5(String str) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] array = md.digest(str.getBytes());
            StringBuffer sb = new StringBuffer();
            for (byte b : array) {
                sb.append(Integer.toHexString((b & 0xFF) | 0x100).substring(1, 3));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
        }
        return null;
    }
}