package src

import java.io.*
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.ArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

object Main {
    private const val DEFAULT_OUTPUT_DIR = "dist"
    private const val DEFAULT_TEMP_DIR = "temp"

    // No clean up flag
    private var CLEAN_DOWNLOADED_CACHE: Boolean = false

    private fun parseArgument(args: Array<String>, flag: String): String? {
        for (i in args.indices) {
            if (args[i] == flag && i + 1 < args.size) {
                return args[i + 1]
            }
        }
        return null
    }

//    private fun hasArgument(args: Array<String>, flag: String): Boolean {
//        return args.contains(flag)
//    }

    private fun hasCleanArgument(args: Array<String>): Boolean {
        return args.contains("--clean")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val urls = ArrayList<String>()
        val urlsFile = parseArgument(args, "-i")
        val outputFile = parseArgument(args, "-o")

        CLEAN_DOWNLOADED_CACHE = hasCleanArgument(args)

        for (arg in args) {
            if (!arg.startsWith("-") && arg != urlsFile && arg != outputFile) {
                urls.add(String.format("%s:%s", outputFile, arg))
            }
        }

        if (urlsFile != null) {
            // Check if file exists
            if (!Files.exists(Paths.get(urlsFile))) {
                println("File not found: $urlsFile")
                return
            }

            BufferedReader(FileReader(urlsFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // Ignore if # or empty
                    if (line!!.startsWith("#") || line!!.isEmpty()) {
                        continue
                    }
                    if (line!!.contains(":")) {
                        val parts = line!!.split(":", limit = 2)
                        urls.add(String.format("%s:%s", parts[0], parts[1].trim()))
                    } else {
                        urls.add(line!!.trim())
                    }
                }
            }
        }

        if (urls.isEmpty()) {
            println("Usage: java HLSDownloader [-t urls_file] <m3u8_url>...")
            return
        }

        // Ensure dist folder exists:
        Files.createDirectories(Paths.get(DEFAULT_OUTPUT_DIR))
        // Ensure temp folder exists:
        Files.createDirectories(Paths.get(DEFAULT_TEMP_DIR))

        for (url in urls) {
            // System.out.println(url);
            val parts = url.split(":", limit = 2)
            val filename = parts[0].takeIf { it.isNotEmpty() } ?: ""

            // Check if file exists in system
            if (Files.exists(Paths.get(DEFAULT_OUTPUT_DIR, filename))) {
                println("File already exists: $filename")
                continue
            }

            // System.out.println("Downloading " + parts[1] + " to " + filename);
            download(filename, parts[1])
        }
    }

    private fun download(filename: String, urlString: String) {
        try {
            val tsFiles = fetchM3U8(urlString)

            val url = URI(urlString).toURL()
            val path = url.path
            val hash = getMD5(path)
            val fileNameParsed = if (filename.isEmpty() || !filename.endsWith(".mp3")) "$hash.mp3" else filename

            downloadTsFiles(hash, urlString, tsFiles)
            mergeFilesToMP3(hash, fileNameParsed, tsFiles)

            // Clean up temp files
            if (CLEAN_DOWNLOADED_CACHE) {
                Files.walk(Paths.get(DEFAULT_TEMP_DIR, hash)).map { it.toFile() }.forEach { it.delete() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Throws(Exception::class)
    private fun downloadTsFiles(identifier: String, playlistURL: String, tsFiles: List<String>) {
        Files.createDirectories(Paths.get(DEFAULT_TEMP_DIR, identifier))

        val lastPath = getLastPath(playlistURL)

        println("Downloading ${tsFiles.size} files...")

        val threads = 25
        val executor: ExecutorService = Executors.newFixedThreadPool(threads) // Adjust the number of threads as needed

        for (tsFile in tsFiles) {
            executor.submit {
                try {
                    // Get index
                    val index = tsFiles.indexOf(tsFile)
                    val fileName = String.format("%04d", index) + ".ts"

                    // Ignore if the file already exists
                    if (Files.exists(Paths.get(DEFAULT_TEMP_DIR, identifier, fileName))) {
                        return@submit
                    }

                    val urlString = playlistURL.replace(lastPath, tsFile)
                    val tsURL = URI(urlString).toURL()
                    val conn = tsURL.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"

                    val input = conn.inputStream
                    Files.copy(input, Paths.get(DEFAULT_TEMP_DIR, identifier, fileName))
                    input.close()

                    println("Downloaded: $fileName")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        executor.shutdown()

        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    @Throws(Exception::class)
    private fun mergeFilesToMP3(identifier: String, fileName: String, tsFiles: List<String>) {
        // Check if exists
        if (Files.exists(Paths.get(DEFAULT_OUTPUT_DIR, fileName))) {
            println("File already exists: $fileName")
            return
        }

        val files = ArrayList<String>()
        for (tsFile in tsFiles) {
            val index = tsFiles.indexOf(tsFile)
            val tsFileName = String.format("%04d", index) + ".ts"
            files.add(Paths.get(DEFAULT_TEMP_DIR, identifier, tsFileName).toString())
        }

        // FFmpeg command to merge the .ts files into a .mp3 with compression
        val pb = ProcessBuilder(
            "ffmpeg",
            "-i",
            "concat:" + files.joinToString("|"),
            "-vn",
            "-acodec",
            "libmp3lame",
            "-ab",
            "256k",
            "-ar",
            "44100",
            "-ac",
            "2",
            "-f",
            "mp3",
            "-y",
            Paths.get(DEFAULT_OUTPUT_DIR, fileName).toString()
        )

        pb.inheritIO()
        val p = pb.start()
        p.waitFor()
    }

    @Throws(Exception::class)
    private fun fetchM3U8(urlString: String): List<String> {
        val url = URI(urlString).toURL()

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        val input = BufferedReader(InputStreamReader(conn.inputStream))
        var inputLine: String?
        val chunkList = ArrayList<String>()
        while (input.readLine().also { inputLine = it } != null) {
            if (!inputLine!!.startsWith("#")) {
                chunkList.add(inputLine!!)
            }
        }
        input.close()

        val last_path = getLastPath(urlString)

        val tsFiles = ArrayList<String>()

        for (chunk in chunkList) {
            val newURL = urlString.replace(last_path, chunk)
            val list = fetchTsFilesList(newURL)
            tsFiles.addAll(list)
        }

        return tsFiles
    }

    @Throws(Exception::class)
    private fun fetchTsFilesList(chunkListURL: String): List<String> {
        // Fetch the .ts files list
        val url = URI(chunkListURL).toURL()

        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        val input = BufferedReader(InputStreamReader(conn.inputStream))
        var inputLine: String?
        val tsFiles = ArrayList<String>()
        while (input.readLine().also { inputLine = it } != null) {
            if (inputLine!!.endsWith(".ts")) {
                tsFiles.add(inputLine!!)
            }
        }
        input.close()

        return tsFiles
    }

    @Throws(Exception::class)
    private fun getLastPath(urlString: String): String {
        val url = URI(urlString).toURL()
        val path = url.path // Get the path part of the URL
        val segments = path.split("/") // Split the path by "/"
        return segments[segments.size - 1] // Get the last segment
    }

    private fun getMD5(str: String): String {
        val md = MessageDigest.getInstance("MD5")
        val array = md.digest(str.toByteArray())
        val sb = StringBuffer()
        for (b in array) {
            sb.append(Integer.toHexString((b.toInt() and 0xFF) or 0x100).substring(1, 3))
        }
        return sb.toString()
    }
}