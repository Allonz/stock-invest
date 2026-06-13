package com.stock.invest.config;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TigerApiConfigTest {

    private final TigerApiConfig config = new TigerApiConfig(null);

    @Test
    @DisplayName("cleanPrivateKey removes PEM headers and whitespace, retains Base64")
    void cleanPrivateKey_removesPemHeaders() throws Exception {
        String rawKey = "-----BEGIN RSA PRIVATE KEY-----\n"
                + "MIIEpAIBAAKCAQEA0gI5V6G7h9P8zDv3\n"
                + "yF4hT2wJkLmN1oPqRrStUvWxYz\n"
                + "-----END RSA PRIVATE KEY-----";

        Method method = TigerApiConfig.class.getDeclaredMethod("cleanPrivateKey", String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(config, rawKey);

        assertNotNull(result);
        assertFalse(result.contains("-----BEGIN"), "must remove PEM BEGIN marker");
        assertFalse(result.contains("-----END"), "must remove PEM END marker");
        assertFalse(result.contains("\n"), "must remove newlines");
        assertFalse(result.contains(" "), "must remove spaces");
        assertTrue(result.startsWith("MIIEpAIBAAKCAQEA0gI5V6G7h9P8zDv3"), "must keep Base64 start");
        assertTrue(result.endsWith("yF4hT2wJkLmN1oPqRrStUvWxYz"), "must keep Base64 end");
    }

    @Test
    @DisplayName("cleanPrivateKey without PEM headers only removes whitespace")
    void cleanPrivateKey_noPemHeaders() throws Exception {
        String rawKey = "MIIEpAIBAAKCAQEA0gI5V6G7h9P8zDv3 yF4hT2wJkLmN1oPqRrStUvWxYz";

        Method method = TigerApiConfig.class.getDeclaredMethod("cleanPrivateKey", String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(config, rawKey);

        assertNotNull(result);
        assertFalse(result.contains(" "), "must remove whitespace");
        assertEquals("MIIEpAIBAAKCAQEA0gI5V6G7h9P8zDv3yF4hT2wJkLmN1oPqRrStUvWxYz", result);
    }

    @Test
    @DisplayName("cleanPrivateKey empty string does not throw")
    void cleanPrivateKey_emptyString() throws Exception {
        Method method = TigerApiConfig.class.getDeclaredMethod("cleanPrivateKey", String.class);
        method.setAccessible(true);
        assertEquals("", method.invoke(config, ""));
    }

    @Test
    @DisplayName("cleanPrivateKey handles PKCS8 format")
    void cleanPrivateKey_pkcs8Format() throws Exception {
        String rawKey = "-----BEGIN PRIVATE KEY-----\n"
                + "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg\n"
                + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz\n"
                + "-----END PRIVATE KEY-----";

        Method method = TigerApiConfig.class.getDeclaredMethod("cleanPrivateKey", String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(config, rawKey);

        assertNotNull(result);
        assertFalse(result.contains("-----BEGIN"), "must remove PEM BEGIN marker");
        assertFalse(result.contains("-----END"), "must remove PEM END marker");
        assertFalse(result.contains("\n"), "must remove newlines");
        assertTrue(result.startsWith("MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg"));
    }

    @Test
    @DisplayName("prepareConfigFiles removed, replaced by resolveCredentials in memory")
    void prepareConfigFiles_noLongerWritesToDisk() {
        boolean hasOld = false;
        boolean hasNew = false;
        for (Method m : TigerApiConfig.class.getDeclaredMethods()) {
            String name = m.getName();
            if ("prepareConfigFiles".equals(name) || "prepareConfigDirectory".equals(name)) {
                hasOld = true;
            }
            if ("resolveCredentials".equals(name)) {
                hasNew = true;
            }
        }
        assertFalse(hasOld, "prepareConfigFiles/prepareConfigDirectory should be removed");
        assertTrue(hasNew, "resolveCredentials should exist as replacement");
    }
}
