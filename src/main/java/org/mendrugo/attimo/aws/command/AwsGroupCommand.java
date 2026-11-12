package org.mendrugo.attimo.aws.command;

import org.aesh.command.CommandResult;
import org.aesh.command.GroupCommandDefinition;
import org.mendrugo.attimo.command.BaseCommand;

@GroupCommandDefinition(
        name = "aws",
        description = "AWS cloud commands",
        groupCommands = {AwsInitCommand.class, AwsRequestCommand.class, AwsStatusCommand.class, AwsConnectCommand.class, AwsDestroyCommand.class},
        generateHelp = true)
public class AwsGroupCommand extends BaseCommand {
    @Override
    protected CommandResult doExecute() {
        return CommandResult.SUCCESS;
    }
}
