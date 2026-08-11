package org.mendrugo.attimo;

import org.mendrugo.attimo.aws.command.AwsGroupCommand;
import org.mendrugo.attimo.command.BaseCommand;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.aesh.AeshRuntimeRunner;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

@QuarkusMain
public class Attimo implements QuarkusApplication
{
    @Override
    public int run(final String... args)
    {
        if (args.length == 0)
        {
            System.out.println("attimo " + BuildInfo.instance().version());
            System.out.println("Use 'ato --help' for usage information.");
            return 0;
        }

        try
        {
            final var result = AeshRuntimeRunner.builder()
                .command(AttimoCommand.class)
                .args(args)
                .execute();

            return result != null ? result.getResultValue() : 1;
        }
        catch (final Exception e)
        {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    @CommandDefinition(
        name = "attimo"
        , description = "Cloud spot instance manager for OpenJDK engineers"
        , groupCommands = {
            AwsGroupCommand.class
        }
        , generateHelp = true
    )
    public static class AttimoCommand extends BaseCommand
    {
        @Option(
            shortName = 'V'
            , name = "version"
            , hasValue = false
            , description = "Display version info"
        )
        boolean versionRequested;

        @Override
        protected CommandResult doExecute()
        {
            if (versionRequested)
            {
                final var info = BuildInfo.instance();
                System.out.println("attimo " + info.version());
                System.out.println(info.runtime());
                return CommandResult.SUCCESS;
            }

            System.out.println("attimo " + BuildInfo.instance().version());
            System.out.println("Use 'ato --help' for usage information.");
            return CommandResult.SUCCESS;
        }
    }
}
