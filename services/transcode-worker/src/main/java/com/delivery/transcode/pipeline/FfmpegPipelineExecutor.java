package com.delivery.transcode.pipeline;

import com.delivery.transcode.model.TranscodeResult;
import com.sun.management.OperatingSystemMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class FfmpegPipelineExecutor {

    private static final Logger log = LoggerFactory.getLogger(FfmpegPipelineExecutor.class);

    @Value("${transcode.vaapi-device:/dev/dri/renderD128}")
    private String vaapiDevice;

    @Value("${transcode.libva-driver:radeonsi}")
    private String libvaDriver;

    @Value("${transcode.hsa-override-gfx:11.0.0}")
    private String hsaOverrideGfx;

    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    public TranscodeResult execute(UUID jobId, UUID uploadId, File inputFile, File outputDir) {
        Instant startTime = Instant.now();
        log.info("Starting HLS transcoding pipeline: jobId={}, uploadId={}, input={}",
                jobId, uploadId, inputFile.getAbsolutePath());

        boolean isHardwareAvailable = isHardwareVaapiAvailable();
        log.info("Hardware acceleration detection: isVaapiAvailable={}, device={}", isHardwareAvailable, vaapiDevice);

        TranscodeExecutionAttempt attempt = null;

        // Attempt 1: Try Hardware VAAPI if device node is accessible
        if (isHardwareAvailable) {
            log.info("Executing AMD RX 7900 GRE Mesa VAAPI hardware pipeline...");
            attempt = runPipeline(inputFile, outputDir, true);
            if (!attempt.success) {
                log.warn("Hardware VAAPI pipeline encountered non-zero exit (code {}). Retrying with software pipeline fallback...", attempt.exitCode);
            }
        }

        // Attempt 2: Run software pipeline if hardware was unavailable or failed
        if (attempt == null || !attempt.success) {
            log.info("Executing software libx264 multi-ladder pipeline...");
            attempt = runPipeline(inputFile, outputDir, false);
        }

        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);

        if (!attempt.success) {
            log.error("FFmpeg pipeline failed completely. Log:\n{}", attempt.output);
            return new TranscodeResult(
                    jobId,
                    uploadId,
                    duration,
                    attempt.averageCpu,
                    attempt.isHardware,
                    List.of(),
                    false,
                    "FFmpeg failure: " + attempt.output
            );
        }

        List<String> generatedFiles = listGeneratedFiles(outputDir);
        log.info("Transcode pipeline completed: duration={}ms, isHardware={}, avgCpu={:.2f}%, filesCount={}",
                duration.toMillis(), attempt.isHardware, attempt.averageCpu, generatedFiles.size());

        return new TranscodeResult(
                jobId,
                uploadId,
                duration,
                attempt.averageCpu,
                attempt.isHardware,
                generatedFiles,
                true,
                null
        );
    }

    private static class TranscodeExecutionAttempt {
        final boolean success;
        final int exitCode;
        final String output;
        final double averageCpu;
        final boolean isHardware;

        TranscodeExecutionAttempt(boolean success, int exitCode, String output, double averageCpu, boolean isHardware) {
            this.success = success;
            this.exitCode = exitCode;
            this.output = output;
            this.averageCpu = averageCpu;
            this.isHardware = isHardware;
        }
    }

    private TranscodeExecutionAttempt runPipeline(File inputFile, File outputDir, boolean isHardware) {
        List<String> command = buildFfmpegCommand(inputFile, outputDir, isHardware);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(outputDir);
        processBuilder.redirectErrorStream(true);

        if (isHardware) {
            processBuilder.environment().put("LIBVA_DRIVER_NAME", libvaDriver);
            processBuilder.environment().put("HSA_OVERRIDE_GFX_VERSION", hsaOverrideGfx);
        }

        AtomicBoolean isRunning = new AtomicBoolean(true);
        List<Double> cpuSamples = new ArrayList<>();
        Thread cpuMonitor = new Thread(() -> {
            while (isRunning.get()) {
                double cpu = osBean.getCpuLoad();
                if (cpu >= 0.0) {
                    cpuSamples.add(cpu * 100.0);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        });
        cpuMonitor.setDaemon(true);
        cpuMonitor.start();

        StringBuilder processOutput = new StringBuilder();
        int exitCode;

        try {
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processOutput.append(line).append("\n");
                }
            }

            exitCode = process.waitFor();
        } catch (Exception e) {
            log.error("Process launch error: {}", e.getMessage(), e);
            return new TranscodeExecutionAttempt(false, -1, e.getMessage(), 0.0, isHardware);
        } finally {
            isRunning.set(false);
        }

        double averageCpu = cpuSamples.isEmpty() ? 0.0 :
                cpuSamples.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        return new TranscodeExecutionAttempt(exitCode == 0, exitCode, processOutput.toString(), averageCpu, isHardware);
    }

    private boolean isHardwareVaapiAvailable() {
        File deviceFile = new File(vaapiDevice);
        return deviceFile.exists() && deviceFile.canRead();
    }

    private List<String> buildFfmpegCommand(File inputFile, File outputDir, boolean isHardware) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-y");

        if (isHardware) {
            command.addAll(Arrays.asList(
                    "-vaapi_device", vaapiDevice,
                    "-hwaccel", "vaapi",
                    "-hwaccel_output_format", "vaapi",
                    "-i", inputFile.getAbsolutePath(),
                    "-filter_complex",
                    "[0:v]split=3[v1][v2][v3];" +
                            "[v1]scale_vaapi=w=-2:h=720[v1out];" +
                            "[v2]scale_vaapi=w=-2:h=480[v2out];" +
                            "[v3]scale_vaapi=w=-2:h=360[v3out]",
                    "-map", "[v1out]", "-c:v:0", "h264_vaapi", "-rc_mode", "CQP", "-global_quality:v", "24", "-b:v:0", "2500k",
                    "-map", "[v2out]", "-c:v:1", "h264_vaapi", "-rc_mode", "CQP", "-global_quality:v", "26", "-b:v:1", "1200k",
                    "-map", "[v3out]", "-c:v:2", "h264_vaapi", "-rc_mode", "CQP", "-global_quality:v", "28", "-b:v:2", "600k",
                    "-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4",
                    "-var_stream_map", "v:0 v:1 v:2",
                    "-master_pl_name", "master.m3u8",
                    "stream_%v.m3u8"
            ));
        } else {
            // High-performance CPU multi-ladder fallback
            command.addAll(Arrays.asList(
                    "-i", inputFile.getAbsolutePath(),
                    "-filter_complex",
                    "[0:v]split=3[v1][v2][v3];" +
                            "[v1]scale=w=-2:h=720[v1out];" +
                            "[v2]scale=w=-2:h=480[v2out];" +
                            "[v3]scale=w=-2:h=360[v3out]",
                    "-map", "[v1out]", "-c:v:0", "libx264", "-b:v:0", "2500k",
                    "-map", "[v2out]", "-c:v:1", "libx264", "-b:v:1", "1200k",
                    "-map", "[v3out]", "-c:v:2", "libx264", "-b:v:2", "600k",
                    "-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4",
                    "-var_stream_map", "v:0 v:1 v:2",
                    "-master_pl_name", "master.m3u8",
                    "stream_%v.m3u8"
            ));
        }

        return command;
    }

    private List<String> listGeneratedFiles(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir.toPath())) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> dir.toPath().relativize(p).toString())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to list generated files in {}: {}", dir, e.getMessage());
            return List.of();
        }
    }
}
