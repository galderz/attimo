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
    /**
     * The cloud identifier used for config/state paths.
     */
    public static final String CLOUD = "aws";

    @Override
    protected CommandResult doExecute()
    {
        System.out.println("AWS cloud commands. Use 'ato aws --help' for available commands.");
        return CommandResult.SUCCESS;
    }
}
