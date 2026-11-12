package org.mendrugo.attimo.bluehat.command;

import org.aesh.command.CommandResult;
import org.aesh.command.GroupCommandDefinition;
import org.mendrugo.attimo.command.BaseCommand;

@GroupCommandDefinition(
        name = "bh",
        description = "Blue Hat cloud commands",
        groupCommands = {BlueHatInitCommand.class, BlueHatRequestCommand.class, BlueHatStatusCommand.class, BlueHatConnectCommand.class, BlueHatDestroyCommand.class},
        generateHelp = true)
public class BlueHatGroupCommand extends BaseCommand {
    @Override
    protected CommandResult doExecute() {
        return CommandResult.SUCCESS;
    }
}
