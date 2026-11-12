package org.mendrugo.attimo.bluehat;

public class BlueHatException extends RuntimeException {
    public BlueHatException(String message) {
        super(message);
    }

    public BlueHatException(String message, Throwable cause) {
        super(message, cause);
    }
}
