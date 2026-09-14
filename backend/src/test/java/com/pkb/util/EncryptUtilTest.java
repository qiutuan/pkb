package com.pkb.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EncryptUtilTest {

    @Test
    void roundTrip() {
        byte[] key = EncryptUtil.randomKey();
        String plain = "sk-abcdef1234567890";
        String enc = EncryptUtil.encrypt(plain, key);
        assertNotEquals(plain, enc);
        assertEquals(plain, EncryptUtil.decrypt(enc, key));
    }

    @Test
    void maskWorks() {
        assertEquals("****", EncryptUtil.mask("short"));
        assertEquals("sk-1****f234", EncryptUtil.mask("sk-1abcdef234"));
    }
}
