# HLS Downloader

HLS (HTTP Live Streaming) is a protocol used to stream video content over the internet. This program is a simple command line that download and merge video and audio streams into a single audio/video file.

Written in Java to be cross-platform, it uses the FFmpeg library to merge the streams.

## Usage (Build from source)

> Please make sure you have Java 18 (or later) and FFmpeg installed on your system.

```bash
./build.sh
java -jar build/downloader.jar <url> <output>
```

### Massively Parallel Download

You can also download multiple streams in parallel by providing a file with the URLs.

```bash
java -jar build/downloader.jar -i video.txt
```

### Arguments

- `-i`: For text file input (Multiple file download)
- `-o`: Output file name (Single file download)
- `--clean`: Clean up temporary files after download
