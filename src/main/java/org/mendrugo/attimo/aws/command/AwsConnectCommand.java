package org.mendrugo.attimo.aws.command;

import org.mendrugo.attimo.aws.Aws;
import org.mendrugo.attimo.command.BaseCommand;
import org.mendrugo.attimo.config.InstanceState;
import org.mendrugo.attimo.ssh.SshSession;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;

@CommandDefinition(
    name = "connect"
    , description = "SSH into the active spot instance"
    , generateHelp = true
)
public class AwsConnectCommand extends BaseCommand
{
    @Override
    protected CommandResult doExecute() throws Exception
    {
        final var state = InstanceState.load(Aws.CLOUD);

        if (!state.hasActiveInstance())
        {
            System.err.println("No active instance. Use 'ato aws request' to launch one.");
            return CommandResult.valueOf(1);
        }

        System.out.println("Connecting to " + state.getInstanceType()
            + " in " + state.getRegion()
            + " (" + state.getPublicIp() + ")...");

        final var session = new SshSession(state.getPublicIp(), Aws.CLOUD);
        final var exitCode = session.connect();

        return exitCode == 0 ? CommandResult.SUCCESS : CommandResult.valueOf(exitCode);
    }
}
