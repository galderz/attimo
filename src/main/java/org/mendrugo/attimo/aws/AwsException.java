package org.mendrugo.attimo.aws;

public class AwsException extends RuntimeException
{
    public AwsException(final String message)
    {
        super(message);
    }

    public AwsException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
