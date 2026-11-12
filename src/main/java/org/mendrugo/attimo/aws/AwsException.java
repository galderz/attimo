package org.mendrugo.attimo.aws;

public class AwsException extends RuntimeException {
    public AwsException(String message) {
        super(message);
    }

    public AwsException(String message, Throwable cause) {
        super(message, cause);
    }
}
