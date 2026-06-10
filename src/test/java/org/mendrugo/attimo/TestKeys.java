package org.mendrugo.attimo;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.EdECPublicKey;
import java.util.Base64;

/**
 * Generates ephemeral SSH key pairs for tests.
 * Keys are created in-memory and never written to disk or stored in source.
 */
public final class TestKeys
{
    private TestKeys() {}

    /**
     * Generate an OpenSSH-formatted ed25519 public key string.
     * The key is generated fresh each call and exists only in memory.
     *
     * @return a string like "ssh-ed25519 AAAA... attimo-test"
     */
    public static String generateEd25519PublicKey()
    {
        try
        {
            final var keyPairGen = KeyPairGenerator.getInstance("Ed25519");
            final var keyPair = keyPairGen.generateKeyPair();
            final var publicKey = (EdECPublicKey)keyPair.getPublic();

            // Encode in OpenSSH format:
            // string "ssh-ed25519" + string <32-byte raw key>
            final var point = publicKey.getPoint();
            final var rawKey = reverseBytes(adjustTo32Bytes(point.getY().toByteArray()));

            // Set high bit if x is odd (EdDSA encoding)
            if (point.isXOdd())
            {
                rawKey[31] |= (byte)0x80;
            }

            final var keyTypeBytes = "ssh-ed25519".getBytes();
            final var blob = new byte[4 + keyTypeBytes.length + 4 + rawKey.length];
            int offset = 0;

            // length-prefixed key type
            putInt(blob, offset, keyTypeBytes.length);
            offset += 4;
            System.arraycopy(keyTypeBytes, 0, blob, offset, keyTypeBytes.length);
            offset += keyTypeBytes.length;

            // length-prefixed raw key
            putInt(blob, offset, rawKey.length);
            offset += 4;
            System.arraycopy(rawKey, 0, blob, offset, rawKey.length);

            return "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob) + " attimo-test";
        }
        catch (final NoSuchAlgorithmException e)
        {
            throw new RuntimeException("Ed25519 not available", e);
        }
    }

    private static byte[] adjustTo32Bytes(final byte[] input)
    {
        if (input.length == 32)
        {
            return input;
        }
        else if (input.length > 32)
        {
            // BigInteger may prepend a zero byte for positive sign
            final var result = new byte[32];
            System.arraycopy(input, input.length - 32, result, 0, 32);
            return result;
        }
        else
        {
            // Pad with leading zeros
            final var result = new byte[32];
            System.arraycopy(input, 0, result, 32 - input.length, input.length);
            return result;
        }
    }

    private static byte[] reverseBytes(final byte[] input)
    {
        final var result = new byte[input.length];
        for (int i = 0; i < input.length; i++)
        {
            result[i] = input[input.length - 1 - i];
        }

        return result;
    }

    private static void putInt(final byte[] buf, final int offset, final int value)
    {
        buf[offset] = (byte)(value >> 24);
        buf[offset + 1] = (byte)(value >> 16);
        buf[offset + 2] = (byte)(value >> 8);
        buf[offset + 3] = (byte)value;
    }
}
