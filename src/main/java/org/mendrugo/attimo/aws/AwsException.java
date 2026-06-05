package org.mendrugo.attimo.aws;

/**
 * Wraps AWS SDK exceptions with clear, actionable error messages.
 */
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
