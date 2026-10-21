package com.delivery.transcode;

import com.delivery.transcode.model.TranscodeResult;
import com.delivery.transcode.pipeline.FfmpegPipelineExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HardwareGpuTranscodeVerificationTest {

    private static final Logger log = LoggerFactory.getLogger(HardwareGpuTranscodeVerificationTest.class);

    @Autowired
    private FfmpegPipelineExecutor pipelineExecutor;

    @Test
    @Order(1)
    @DisplayName("Verification Gate: Transcode 30s 1080p MP4 reel into 3-resolution HLS ladder (720p, 480p, 360p)")
    void testTranscodePipelineExecution() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();

        Path tempDir = Files.createTempDirectory("transcode-gate-test-");
        File inputFile = tempDir.resolve("input_30s_1080p.mp4").toFile();
        File outputDir = tempDir.resolve("hls").toFile();
        outputDir.mkdirs();

        try {
            // Step 1: Prepare synthetic 30s 1080p MP4 reel
            File cachedVideo = new File("/tmp/test_30s_1080p.mp4");
            if (cachedVideo.exists() && cachedVideo.length() > 0) {
                Files.copy(cachedVideo.toPath(), inputFile.toPath());
            } else {
                log.info("Generating synthetic 30s 1080p MP4 test video with ffmpeg...");
                Process gen = new ProcessBuilder(
                        "ffmpeg", "-y",
                        "-f", "lavfi", "-i", "testsrc=duration=30:size=1920x1080:rate=30",
                        "-c:v", "libx264", "-pix_fmt", "yuv420p",
                        inputFile.getAbsolutePath()
                ).redirectErrorStream(true).start();
                int genExit = gen.waitFor();
                assertThat(genExit).isEqualTo(0);
            }

            assertThat(inputFile.exists()).isTrue();
            assertThat(inputFile.length()).isGreaterThan(0L);

            log.info("Test video prepared: size={} bytes", inputFile.length());

            // Step 2: Execute transcode pipeline
            TranscodeResult result = pipelineExecutor.execute(jobId, uploadId, inputFile, outputDir);

            log.info("Transcode result: success={}, duration={}ms, isHardware={}, avgCpu={:.2f}%, filesCount={}",
                    result.success(), result.duration().toMillis(), result.isHardwareAccelerated(),
                    result.averageCpuUtilizationPercent(), result.generatedFiles().size());

            // Step 3: Verify execution success
            assertThat(result.success())
                    .as("Transcoding must succeed. Error: %s", result.errorMessage())
                    .isTrue();

            // Step 4: Verify 3-resolution HLS ladder deliverables
            File masterPlaylist = new File(outputDir, "master.m3u8");
            assertThat(masterPlaylist.exists())
                    .as("master.m3u8 must be generated")
                    .isTrue();

            String masterContent = Files.readString(masterPlaylist.toPath());
            log.info("master.m3u8 contents:\n{}", masterContent);

            // Verify the 3-resolution ladder
            assertThat(masterContent).contains("stream_0.m3u8");
            assertThat(masterContent).contains("stream_1.m3u8");
            assertThat(masterContent).contains("stream_2.m3u8");

            File stream0 = new File(outputDir, "stream_0.m3u8");
            File stream1 = new File(outputDir, "stream_1.m3u8");
            File stream2 = new File(outputDir, "stream_2.m3u8");

            assertThat(stream0.exists()).isTrue();
            assertThat(stream1.exists()).isTrue();
            assertThat(stream2.exists()).isTrue();

            // Verify fmp4 segments are produced
            assertThat(result.generatedFiles().stream().anyMatch(f -> f.endsWith(".m4s"))).isTrue();

            if (result.isHardwareAccelerated()) {
                log.info("Hardware AMD RX 7900 GRE transcode duration: {} ms, avgCpu={:.2f}%",
                        result.duration().toMillis(), result.averageCpuUtilizationPercent());
                assertThat(result.duration().toMillis())
                        .as("Hardware transcode must complete in < 3000ms (3.0 seconds)")
                        .isLessThan(3000L);
                assertThat(result.averageCpuUtilizationPercent())
                        .as("Hardware transcode must consume minimal CPU (< 10%)")
                        .isLessThan(10.0);
            }

        } finally {
            deleteDir(tempDir.toFile());
        }
    }

    private void deleteDir(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteDir(f);
                }
            }
        }
        file.delete();
    }
}
