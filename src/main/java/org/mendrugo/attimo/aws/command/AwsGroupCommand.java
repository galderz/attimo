package org.mendrugo.attimo.aws.command;

import org.mendrugo.attimo.command.BaseCommand;
import org.aesh.command.CommandResult;
import org.aesh.command.GroupCommandDefinition;

/**
 * Groups all AWS-specific commands under the "aws" subcommand.
 * Usage: ato aws init, ato aws request, ato aws status, etc.
 */
@GroupCommandDefinition(
    name = "aws"
    , description = "AWS cloud commands"
    , groupCommands = {
        AwsInitCommand.class
        , AwsRequestCommand.class
        , AwsStatusCommand.class
        , AwsConnectCommand.class
        , AwsDestroyCommand.class
    }
    , generateHelp = true
)
public class AwsGroupCommand extends BaseCommand
{
    @Override
    protected CommandResult doExecute()
    {
        return CommandResult.SUCCESS;
    }
}
