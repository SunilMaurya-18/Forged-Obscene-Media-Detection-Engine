package com.forgeddetect.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Phase 18 "check before you post" settings, bound from {@code prescreen.*}. */
@ConfigurationProperties(prefix = "prescreen")
public class PrescreenProperties {

    /** Max Hamming distance (of 64 bits) for a pHash to count as a match. */
    private int phashMaxDistance = 8;

    /** Max Hamming distance (of 256 bits) for PDQ, the StopNCII image hash; 31 is Meta's recommended cut-off. */
    private int pdqMaxDistance = 31;

    /** pHashes inserted as INTERNAL_CONFIRMED at startup when missing (demo: a synthetic test pattern). */
    private List<String> seedHashes = new ArrayList<>();

    public int getPhashMaxDistance() {
        return phashMaxDistance;
    }

    public void setPhashMaxDistance(int phashMaxDistance) {
        this.phashMaxDistance = phashMaxDistance;
    }

    public int getPdqMaxDistance() {
        return pdqMaxDistance;
    }

    public void setPdqMaxDistance(int pdqMaxDistance) {
        this.pdqMaxDistance = pdqMaxDistance;
    }

    public List<String> getSeedHashes() {
        return seedHashes;
    }

    public void setSeedHashes(List<String> seedHashes) {
        this.seedHashes = seedHashes;
    }
}
