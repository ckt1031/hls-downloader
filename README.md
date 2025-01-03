# HLS Downloader

HLS (HTTP Live Streaming) is a protocol used to stream video content over the internet. This program is a simple command line that download and merge video and audio streams into a single audio/video file.

Written in Java to be cross-platform, it uses the ffmpeg library to merge the streams.

## Usage (Build from source)

> Please make sure you have Java 18 (or later), Kotlin and `ffmpeg` installed on your system.

```bash
kotlinc src/Main.kt -include-runtime -d downloader.jar
java -jar downloader.jar <url> <output>
```

### Massively Parallel Download

You can also download multiple streams in parallel by providing a file with the URLs.

```bash
java -jar downloader.jar -i video.txt
```

### Arguments

- `-i`: For text file input (Multiple file download)
- `-o`: Output file name (Single file download)
- `--clean`: Clean up temporary files after download
