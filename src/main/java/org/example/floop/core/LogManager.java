package org.example.floop.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


public final class LogManager implements AutoCloseable {

    private static LogManager INSTANCE = new LogManager();

    public static LogManager getInstance() {
        return INSTANCE;
    }

    static void resetInstance() {
        try {
            if (INSTANCE != null) {
                INSTANCE.close();
            }
        } catch (Exception e) {
            // Handle or log exception
        }
        INSTANCE = new LogManager();
    }

    public enum Level {
        INFO,
        ERROR,
        METRIC
    }
    private record LogMessage(
            LocalDateTime timestamp,
            Level level,
            String message,
            Throwable error
    ) {}

    private static final int QUEUE_SIZE = 1000;
    private final BlockingQueue<LogMessage> messageQueue;
    private final Path path;
    private DateTimeFormatter formatter;
    private final AtomicBoolean isRunning;
    private final Thread processor;

    private LogManager() {
        this.messageQueue = new ArrayBlockingQueue<>(QUEUE_SIZE);
        this.path = Paths.get(System.getProperty("PIPELINE_LOG_PATH", "logs"));
        this.formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        this.isRunning = new AtomicBoolean(true);
        this.processor = Thread.ofVirtual().name("log-processor").unstarted(this::processLogs);

        initialize();
    }

    private void initialize() {
        try{
            Files.createDirectories(path.resolve("info"));
            Files.createDirectories(path.resolve("error"));
            Files.createDirectories(path.resolve("metric"));
            processor.start();

        } catch (Exception e) {
            System.err.println(STR."Failed to initialize log manager: \{e.getMessage()}");
            throw new RuntimeException("Logs: init failed",e);
        }
    }

    private void log(Level level, String message, Throwable error) {
        try{
            messageQueue.offer(
                    new LogMessage(LocalDateTime.now(), level, message, error),
                    100,
                    TimeUnit.MILLISECONDS
            );
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
            System.err.println(STR."Interrupted while waiting for log message: \{e.getMessage()}");
        }
    }

    public void info(String message) {
        log(Level.INFO, message, null);
    }
    public void error(String message, Throwable error) {
        log(Level.ERROR, message, error);
    }
    public void metric(String message) {
        log(Level.METRIC, message, null);
    }
    private void processLogs() {
        while(isRunning.get() || !messageQueue.isEmpty()) {
            try{
                LogMessage message = messageQueue.poll(100, TimeUnit.MILLISECONDS);
                if (message != null) {
                    writeLog(message);
                }
            } catch (InterruptedException e){
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void writeLog(LogMessage message) {
        String formattedMessage = formatMessage(message);
        Path logFile = getLogFile(message.level());

        try{
            Files.writeString(
                    logFile,
                    formattedMessage + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to write logs. " ,e);
        }
    }

    private String formatMessage(LogMessage message) {
        String base = STR."[\{message.timestamp().format(formatter)}] [\{message.level()}] [\{message.message}]";
        if (message.error() != null){
            return STR."\{base} - Error: \{message.error().getMessage()}";
        }
        return base;
    }

    private Path getLogFile(Level level) {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String directory =switch (level){
            case INFO -> "info";
            case ERROR -> "error";
            case METRIC -> "metric";
        };
        return path.resolve(directory).resolve(STR."pipeline_\{level.name().toLowerCase()}_\{date}.log");
    }
    @Override
    public void close() throws Exception {
        isRunning.set(false);
        try{
            processor.join(5000);
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
    }
}

