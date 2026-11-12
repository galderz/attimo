package org.mendrugo.attimo;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.util.Base64;

public final class TestKeys {
    private TestKeys() {}

    public static String generateEd25519PublicKey() {
        try {
            final KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("Ed25519");
            final KeyPair keyPair = keyPairGen.generateKeyPair();
            final EdECPublicKey publicKey = (EdECPublicKey) keyPair.getPublic();

            final EdECPoint point = publicKey.getPoint();
            final byte[] rawKey = reverseBytes(adjustTo32Bytes(point.getY().toByteArray()));

            if (point.isXOdd()) {
                rawKey[31] |= (byte) 0x80;
            }

            final byte[] keyTypeBytes = "ssh-ed25519".getBytes();
            final byte[] blob = new byte[4 + keyTypeBytes.length + 4 + rawKey.length];
            int offset = 0;

            putInt(blob, offset, keyTypeBytes.length);
            offset += 4;
            System.arraycopy(keyTypeBytes, 0, blob, offset, keyTypeBytes.length);
            offset += keyTypeBytes.length;

            putInt(blob, offset, rawKey.length);
            offset += 4;
            System.arraycopy(rawKey, 0, blob, offset, rawKey.length);

            return "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob) + " attimo-test";
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Ed25519 not available", e);
        }
    }

    private static byte[] adjustTo32Bytes(byte[] input) {
        if (input.length == 32) {
            return input;
        } else if (input.length > 32) {
            final byte[] result = new byte[32];
            System.arraycopy(input, input.length - 32, result, 0, 32);
            return result;
        } else {
            final byte[] result = new byte[32];
            System.arraycopy(input, 0, result, 32 - input.length, input.length);
            return result;
        }
    }

    private static byte[] reverseBytes(byte[] input) {
        final byte[] result = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            result[i] = input[input.length - 1 - i];
        }

        return result;
    }

    private static void putInt(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value >> 24);
        buf[offset + 1] = (byte) (value >> 16);
        buf[offset + 2] = (byte) (value >> 8);
        buf[offset + 3] = (byte) value;
    }
}
