package com.forgeddetect.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MediaRoutingTest {

    @Test
    void videoIsDetectedByTypeExtensionOrMagicBytes() {
        assertThat(MediaKinds.isVideo("video/webm", "x.bin", new byte[0])).isTrue();
        assertThat(MediaKinds.isVideo("application/octet-stream", "clip.MOV", new byte[0])).isTrue();
        assertThat(MediaKinds.isVideo(null, null, box("isom"))).isTrue();
        assertThat(MediaKinds.isVideo(null, null, new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 0, 0, 0, 0, 0, 0, 0, 0})).isTrue();
        assertThat(MediaKinds.isVideo("image/jpeg", "x.jpg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0}))
                .isFalse();
        assertThat(MediaKinds.isVideo(null, "photo.heic", box("heic"))).isFalse();
    }

    @Test
    void routingPicksTheConfiguredAggregate() throws Exception {
        var agg = new ObjectMapper().readTree("{\"forgery\":{\"max\":0.97,\"topk_mean\":0.71},\"explicit\":{\"max\":0.04,\"topk_mean\":0.03}}");
        assertThat(VideoProperties.pick(agg, "forgery", VideoProperties.Aggregate.TOPK_MEAN, 0)).isEqualTo(0.71);
        assertThat(VideoProperties.pick(agg, "forgery", VideoProperties.Aggregate.MAX, 0)).isEqualTo(0.97);
        assertThat(VideoProperties.pick(agg, "explicit", VideoProperties.Aggregate.MAX, 0)).isEqualTo(0.04);
        assertThat(VideoProperties.pick(null, "forgery", VideoProperties.Aggregate.MAX, 0.5)).isEqualTo(0.5);
    }

    @Test
    void noticeNumbersNormaliseLikeCaseNumbers() {
        assertThat(CaseNumbers.normalize("UPL", "upl 7k3q 9xpa")).isEqualTo("UPL-7K3Q-9XPA");
        assertThat(CaseNumbers.normalize("UPL", "7K3Q9XPA")).isEqualTo("UPL-7K3Q-9XPA");
        assertThat(CaseNumbers.normalize("UPL", "UPL-7K3Q-9XP0")).isNull();
        assertThat(CaseService.normalizeCaseNumber("fmd-7k3q-9xpa")).isEqualTo("FMD-7K3Q-9XPA");
        assertThat(CaseNumbers.isPinShaped("123456")).isTrue();
        assertThat(CaseNumbers.isPinShaped("12345a")).isFalse();
    }

    private static byte[] box(String brand) {
        byte[] out = new byte[16];
        out[3] = 16;
        System.arraycopy("ftyp".getBytes(StandardCharsets.ISO_8859_1), 0, out, 4, 4);
        System.arraycopy(brand.getBytes(StandardCharsets.ISO_8859_1), 0, out, 8, 4);
        return out;
    }
}
