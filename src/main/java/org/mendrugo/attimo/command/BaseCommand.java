package org.mendrugo.attimo.command;

import org.aesh.command.Command;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

public abstract class BaseCommand implements Command<CommandInvocation>
{
    protected CommandInvocation commandInvocation;

    @Override
    public CommandResult execute(final CommandInvocation invocation) throws InterruptedException
    {
        this.commandInvocation = invocation;
        try
        {
            return doExecute();
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw e;
        }
        catch (final Exception e)
        {
            System.err.println("Error: " + e.getMessage());
            return CommandResult.valueOf(1);
        }
    }

    protected CommandResult doExecute() throws Exception
    {
        return CommandResult.SUCCESS;
    }
}
