package com.forgeddetect.signing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks the report-signing key provider from {@code report.signing.provider} ({@code file} | {@code vault}). */
@Configuration
public class SigningConfig {

    @Bean
    @ConditionalOnProperty(name = "report.signing.provider", havingValue = "vault")
    public SigningKeyProvider vaultSigningKeyProvider(
            @Value("${report.signing.vault.address:}") String address,
            @Value("${report.signing.vault.token:}") String token,
            @Value("${report.signing.vault.namespace:}") String namespace,
            @Value("${report.signing.vault.mount:transit}") String mount,
            @Value("${report.signing.vault.key-name:fmd-report-signing}") String keyName,
            @Value("${report.signing.vault.timeout:PT5S}") Duration timeout,
            ObjectMapper mapper) {
        return new VaultTransitSigningKeyProvider(address, token, namespace, mount, keyName, timeout, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "report.signing.provider", havingValue = "file", matchIfMissing = true)
    public SigningKeyProvider fileSigningKeyProvider(@Value("${report.signing.key-dir:./data/signing-keys}") String dir) {
        return new FileSigningKeyProvider(Path.of(dir));
    }
}
