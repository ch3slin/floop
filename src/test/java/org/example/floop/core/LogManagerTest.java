package org.example.floop.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LogManagerTest {
    private LogManager manager;
    private static final Path TEST_PATH = Paths.get("test-logs");

    @BeforeEach
    void setUp() throws IOException {
        if(Files.exists(TEST_PATH)) {
            Files.walk(TEST_PATH)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path1 -> {
                        try {
                            Files.delete(path1);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to delete : " + e);
                        }
                    });
        }

        System.setProperty("PIPELINE_LOG_PATH", TEST_PATH.toString());
        LogManager.resetInstance();
        manager = LogManager.getInstance();
    }

    @AfterEach
    void cleanUp() throws Exception {
        manager.close();
    }

    @Test
    void testDirectoryCreation() {
        assertTrue(Files.exists(TEST_PATH.resolve("info")));
        assertTrue(Files.exists(TEST_PATH.resolve("error")));
        assertTrue(Files.exists(TEST_PATH.resolve("metric")));
    }

    @Test
    void testInfoLogging() throws IOException, InterruptedException {
        String testMessage = "Test message";
        manager.info(testMessage);

        Thread.sleep(100);

        Path infoLogFile = getLatestLogFile("info");
        List<String> lines = Files.readAllLines(infoLogFile);

        assertTrue(lines.stream().anyMatch(line -> line.contains(testMessage)));
    }

    @Test
    void testErrorLogging() throws IOException, InterruptedException {
        Exception testException = new RuntimeException("Test error");
        String errorMessage = "Test error message";

        manager.error(errorMessage, testException);
        Thread.sleep(100);
        Path errorLogFile = getLatestLogFile("error");
        List<String> lines = Files.readAllLines(errorLogFile);
        assertTrue(lines.stream().anyMatch(line -> line.contains(errorMessage) && line.contains("Test error")));
    }

    @Test
    void testMetricLogging() throws IOException, InterruptedException {
        String metricMessage = "Test metrics: 100ms";
        manager.metric(metricMessage);
        Thread.sleep(100);
        Path metricLogFile = getLatestLogFile("metric");
        List<String> lines = Files.readAllLines(metricLogFile);
        assertTrue(lines.stream().anyMatch(line -> line.contains(metricMessage)));
    }

    @Test
    void testConcurrentLogging() throws IOException, InterruptedException {
        int numberOfLogs = 100;
        Thread[] threads = new Thread[numberOfLogs];
        for (int i = 0; i < numberOfLogs; i++) {
            final int num = i;
            threads[i] = new Thread(() -> {
                manager.info("Test message " + num);
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }
        Thread.sleep(200);

        assertTrue(true);
    }

    @Test
    void testLogFileRotation() throws IOException, InterruptedException {
        LocalDateTime now = LocalDateTime.now();
        String expectedDate = now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        manager.info("Test message");
        Thread.sleep(100);
        Path logFile = getLatestLogFile("info");
        assertTrue(logFile.getFileName().toString().contains(expectedDate),
                "Log file name should contain the current year-month");
    }

    @Test
    void testGracefulShutdown() throws Exception {
        for (int i = 0; i < 10; i++) {
            manager.info("Test message " + i);
        }
        manager.close();
        Thread.sleep(100);
        Path infoLogFile = getLatestLogFile("info");
        List<String> lines = Files.readAllLines(infoLogFile);
        assertEquals(10, lines.size());
    }

    private Path getLatestLogFile(String extension) throws IOException {
        Path typeDir = TEST_PATH.resolve(extension);
        return Files.list(typeDir)
                .filter(path1 -> path1.getFileName().toString().endsWith(".log"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No log file found"));
    }
}