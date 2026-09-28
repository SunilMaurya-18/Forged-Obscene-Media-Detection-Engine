package com.forgeddetect.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountPseudonymsTest {

    private static AccountPseudonyms withKey(String key) {
        InvestigationProperties props = new InvestigationProperties();
        props.setPseudonymKey(key);
        return new AccountPseudonyms(props);
    }

    private final AccountPseudonyms pseudonyms = withKey("test-key-0123456789abcdef0123456789abcdef");

    @Test
    void sameAccountOnSamePlatformIsStableAndNeverTheRawId() {
        String a = pseudonyms.of("pixelgram", "user-42");
        assertThat(a).matches("[0-9a-f]{64}").doesNotContain("user-42");
        assertThat(pseudonyms.of("pixelgram", "  user-42 ")).isEqualTo(a);
        assertThat(pseudonyms.of("pixelgram", "user-43")).isNotEqualTo(a);
    }

    @Test
    void scopedByPlatformAndByKey() {
        assertThat(pseudonyms.of("chirp", "user-42")).isNotEqualTo(pseudonyms.of("pixelgram", "user-42"));
        assertThat(withKey("another-key-0123456789abcdef0123456789ab").of("pixelgram", "user-42"))
                .isNotEqualTo(pseudonyms.of("pixelgram", "user-42"));
    }

    @Test
    void blankIsNoAccountAndOverlongIsRejected() {
        assertThat(pseudonyms.of("pixelgram", null)).isNull();
        assertThat(pseudonyms.of("pixelgram", "   ")).isNull();
        assertThatThrownBy(() -> pseudonyms.of("pixelgram", "x".repeat(AccountPseudonyms.MAX_ACCOUNT_ID + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AccountPseudonyms.shortForm(pseudonyms.of("pixelgram", "user-42"))).hasSize(12);
        assertThatThrownBy(() -> withKey(" ")).isInstanceOf(IllegalStateException.class);
    }
}
