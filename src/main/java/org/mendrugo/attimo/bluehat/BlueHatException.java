package org.mendrugo.attimo.bluehat;

/**
 * Typed exception for Blue Hat cloud operations.
 */
public class BlueHatException extends RuntimeException
{
    public BlueHatException(final String message)
    {
        super(message);
    }

    public BlueHatException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
