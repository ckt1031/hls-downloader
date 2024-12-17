# Build to Jar file from source code java
# src/Main.java, main class: src.Main
javac src/Main.java
jar cfe build/downloader.jar src.Main src/Main.class
java -jar build/downloader.jar